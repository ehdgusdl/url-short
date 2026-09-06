package com.example.urlshort.cache;

import com.example.urlshort.dto.UrlView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 쓰기 경로(url-api)가 소유하는 캐시 조작. L2(Redis)에 직접 쓰고 무효화를 Pub/Sub로 브로드캐스트한다.
 *
 * <p>url-api에는 L1이 없다. L1은 리다이렉트를 서빙하는 redirect 인스턴스에만 존재하고,
 * 여기서 발행한 무효화 메시지를 받아 스스로 비운다.
 */
@Component
public class UrlCacheWriter {

    /** SCAN 한 번에 훑고 지울 키 수. 너무 크면 KEYS와 다를 게 없고, 너무 작으면 왕복만 는다. */
    private static final int SCAN_BATCH = 500;

    private final RedisTemplate<String, UrlView> l2;
    private final StringRedisTemplate publisher;
    private final Duration primeTtl;
    private final Duration tombstoneTtl;

    /**
     * Write-Through 선입력(prime) 적용 여부. 생성 직후 404 검증의 음성대조군 스위치다.
     * 끄면 생성 직후 조회가 Replication Lag 구간의 Replica로 내려가 404가 나는 원래 문제를 재현한다.
     */
    private final AtomicBoolean primeEnabled;

    private final Counter invPublishedSingle;
    private final Counter invPublishedAll;

    public UrlCacheWriter(RedisTemplate<String, UrlView> l2,
                          StringRedisTemplate publisher,
                          @Value("${app.cache.prime-ttl:10s}") Duration primeTtl,
                          @Value("${app.cache.tombstone-ttl:10s}") Duration tombstoneTtl,
                          @Value("${app.cache.prime-enabled:true}") boolean primeEnabled,
                          MeterRegistry registry) {
        this.l2 = l2;
        this.publisher = publisher;
        this.primeTtl = primeTtl;
        this.tombstoneTtl = tombstoneTtl;
        this.primeEnabled = new AtomicBoolean(primeEnabled);
        this.invPublishedSingle = Counter.builder("urlcache.invalidations.published").tag("scope", "single").register(registry);
        this.invPublishedAll = Counter.builder("urlcache.invalidations.published").tag("scope", "all").register(registry);
        registry.gauge("urlcache.prime.enabled", this.primeEnabled, b -> b.get() ? 1.0 : 0.0);
    }

    /**
     * 생성 직후 조회를 캐시 히트로 흡수하기 위한 L2 선입력. TTL은 짧게 잡아 미조회 키의 캐시 오염을 막는다.
     */
    public void prime(String shortCode, UrlView view) {
        // 방금 만든 코드다. 음성 캐시가 남아 있으면 선입력을 켜든 끄든 그 TTL 동안 404가 나간다.
        publisher.delete(CacheChannels.MISS_PREFIX + shortCode);
        if (!primeEnabled.get()) {
            return;
        }
        l2.opsForValue().set(CacheChannels.KEY_PREFIX + shortCode, view, primeTtl);
    }

    /**
     * 단일 키 즉시 무효화: L2 제거 + 묘비 표시 후 전 redirect 인스턴스에 브로드캐스트.
     *
     * <p>묘비는 복제 지연 구간에 Replica의 옛 행이 캐시로 되살아나는 것을 막는다.
     * TTL은 Replication Lag(p99 약 300ms)보다 넉넉히 크게, 그러나 짧게 잡는다.
     */
    public void invalidate(String shortCode) {
        publisher.opsForValue().set(CacheChannels.TOMBSTONE_PREFIX + shortCode, "1", tombstoneTtl);
        l2.delete(CacheChannels.KEY_PREFIX + shortCode);
        publisher.convertAndSend(CacheChannels.INVALIDATION_CHANNEL, shortCode);
        invPublishedSingle.increment();
    }

    /**
     * 전체 무효화: 만료 정리 등 대량 변경 시 사용.
     *
     * <p>KEYS 는 단일 스레드 Redis를 키스페이스 크기만큼 붙잡는다. 100만 키 규모에서는
     * 그 사이 모든 redirect의 L2 조회가 멈추므로 SCAN으로 나눠 훑고 나눠 지운다.
     */
    public void invalidateAll() {
        ScanOptions options = ScanOptions.scanOptions()
                .match(CacheChannels.KEY_PREFIX + "*").count(SCAN_BATCH).build();
        List<String> batch = new ArrayList<>(SCAN_BATCH);
        try (Cursor<String> cursor = l2.scan(options)) {
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= SCAN_BATCH) {
                    l2.delete(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            l2.delete(batch);
        }
        publisher.convertAndSend(CacheChannels.INVALIDATION_CHANNEL, CacheChannels.INVALIDATE_ALL);
        invPublishedAll.increment();
    }

    public boolean isPrimeEnabled() {
        return primeEnabled.get();
    }

    /** 선입력 런타임 토글(검증용 음성대조군). */
    public void setPrimeEnabled(boolean enabled) {
        primeEnabled.set(enabled);
    }
}
