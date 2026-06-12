package com.example.urlshort.cache;

import com.example.urlshort.config.RedisConfig;
import com.example.urlshort.dto.UrlView;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * L1(로컬 Caffeine) + L2(Redis) 2단 캐시.
 *
 * <p>조회는 L1 → L2 → 로더(DB) 순으로 폴백하며, 미스 시 {@link SingleFlight}로 동일 키 중복 적재를 막는다.
 * 무효화는 L1/L2를 모두 제거한 뒤 Pub/Sub로 전 인스턴스에 브로드캐스트해, TTL 만료를 기다리지 않고
 * 모든 서버의 L1을 즉시 비운다.
 */
@Component
public class LayeredUrlCache {

    static final String KEY_PREFIX = "url:";

    private final Cache<String, UrlView> l1;
    private final RedisTemplate<String, UrlView> l2;
    private final StringRedisTemplate publisher;
    private final SingleFlight singleFlight;
    private final Duration l2Ttl;

    public LayeredUrlCache(@Qualifier("localUrlCache") Cache<String, UrlView> l1,
                           RedisTemplate<String, UrlView> l2,
                           StringRedisTemplate publisher,
                           SingleFlight singleFlight,
                           @Value("${app.cache.l2-ttl:1h}") Duration l2Ttl) {
        this.l1 = l1;
        this.l2 = l2;
        this.publisher = publisher;
        this.singleFlight = singleFlight;
        this.l2Ttl = l2Ttl;
    }

    public Optional<UrlView> get(String shortCode, Supplier<Optional<UrlView>> loader) {
        UrlView fromL1 = l1.getIfPresent(shortCode);
        if (fromL1 != null) {
            return Optional.of(fromL1);
        }

        UrlView fromL2 = l2.opsForValue().get(KEY_PREFIX + shortCode);
        if (fromL2 != null) {
            l1.put(shortCode, fromL2);
            return Optional.of(fromL2);
        }

        return singleFlight.execute(shortCode, () -> {
            // 대기 중 다른 스레드가 채웠을 수 있으므로 L1 재확인.
            UrlView racedL1 = l1.getIfPresent(shortCode);
            if (racedL1 != null) {
                return Optional.of(racedL1);
            }
            Optional<UrlView> loaded = loader.get();
            loaded.ifPresent(view -> {
                l2.opsForValue().set(KEY_PREFIX + shortCode, view, l2Ttl);
                l1.put(shortCode, view);
            });
            return loaded;
        });
    }

    /** 단일 키 즉시 무효화: 로컬·Redis 제거 후 전 인스턴스에 무효화 브로드캐스트. */
    public void invalidate(String shortCode) {
        l1.invalidate(shortCode);
        l2.delete(KEY_PREFIX + shortCode);
        publisher.convertAndSend(RedisConfig.INVALIDATION_CHANNEL, shortCode);
    }

    /** 전체 무효화: 만료 정리 등 대량 변경 시 사용. */
    public void invalidateAll() {
        l1.invalidateAll();
        Set<String> keys = l2.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            l2.delete(keys);
        }
        publisher.convertAndSend(RedisConfig.INVALIDATION_CHANNEL, CacheInvalidationListener.INVALIDATE_ALL);
    }

    /** Pub/Sub 수신 시 로컬(L1)만 비운다. L2는 발행 인스턴스가 이미 제거했다. */
    void evictLocal(String shortCode) {
        l1.invalidate(shortCode);
    }

    void evictLocalAll() {
        l1.invalidateAll();
    }
}
