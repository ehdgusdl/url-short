package com.example.urlshort.cache;

import com.example.urlshort.config.RedisConfig;
import com.example.urlshort.dto.UrlView;
import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Duration primeTtl;

    /**
     * L1(로컬 Caffeine) 사용 여부. 벤치마크에서 재시작 없이 "Redis 단독" ↔ "레이어드"를 전환하기 위한 런타임 스위치.
     * 끄면 조회가 L2(Redis) → DB만 타므로 히트마다 앱↔Redis 네트워크 왕복이 발생하는 순수 Redis 캐시가 된다.
     */
    private final AtomicBoolean l1Enabled;

    /**
     * Pub/Sub 무효화 전파 적용 여부. 무효화 검증의 <b>음성대조군</b>이다.
     * 끄면 구독 메시지를 받아도 L1을 비우지 않아, "Pub/Sub가 없거나 메시지를 놓친 인스턴스"를 재현한다.
     * 이 상태에서 다른 인스턴스가 삭제한 키를 조회하면 L1에 남은 값으로 Stale 302를 계속 응답한다
     * (발행 인스턴스가 L2는 이미 지웠으므로, 그 Stale은 반드시 L1에서 온 것 = L1 잔존의 증거).
     */
    private final AtomicBoolean propagationEnabled;

    // 계층별 요청/히트/로드 계측. Grafana에서 계층별 적중률·서빙 분포·Redis 호출 절감을 계산한다.
    private final Counter l1Requests;   // L1을 조회한 횟수(useL1일 때만)
    private final Counter l1Hits;       // L1에서 값을 찾은 횟수
    private final Counter l2Requests;   // L2(Redis)를 조회한 횟수
    private final Counter l2Hits;       // L2에서 값을 찾은 횟수
    private final Counter dbLoads;      // 로더(DB)까지 내려간 횟수

    // 무효화 전파 계측. 발행(publisher) vs 수신·적용(subscriber)을 인스턴스별로 대조해
    // Pub/Sub 전파와 유실(at-most-once)을 Grafana에서 정량화한다.
    private final Counter invPublishedSingle; // 단일 키 무효화를 발행한 횟수
    private final Counter invPublishedAll;    // 전체 무효화를 발행한 횟수
    private final Counter invReceivedSingle;  // 단일 키 무효화를 수신·적용(L1 evict)한 횟수
    private final Counter invReceivedAll;      // 전체 무효화를 수신·적용한 횟수

    public LayeredUrlCache(@Qualifier("localUrlCache") Cache<String, UrlView> l1,
                           RedisTemplate<String, UrlView> l2,
                           StringRedisTemplate publisher,
                           SingleFlight singleFlight,
                           @Value("${app.cache.l2-ttl:1h}") Duration l2Ttl,
                           @Value("${app.cache.prime-ttl:10s}") Duration primeTtl,
                           @Value("${app.cache.l1-enabled:true}") boolean l1Enabled,
                           MeterRegistry registry) {
        this.l1 = l1;
        this.l2 = l2;
        this.publisher = publisher;
        this.singleFlight = singleFlight;
        this.l2Ttl = l2Ttl;
        this.primeTtl = primeTtl;
        this.l1Enabled = new AtomicBoolean(l1Enabled);
        this.propagationEnabled = new AtomicBoolean(true);
        this.l1Requests = Counter.builder("urlcache.requests").tag("layer", "l1").register(registry);
        this.l1Hits = Counter.builder("urlcache.hits").tag("layer", "l1").register(registry);
        this.l2Requests = Counter.builder("urlcache.requests").tag("layer", "l2").register(registry);
        this.l2Hits = Counter.builder("urlcache.hits").tag("layer", "l2").register(registry);
        this.dbLoads = Counter.builder("urlcache.loads").register(registry);
        this.invPublishedSingle = Counter.builder("urlcache.invalidations.published").tag("scope", "single").register(registry);
        this.invPublishedAll = Counter.builder("urlcache.invalidations.published").tag("scope", "all").register(registry);
        this.invReceivedSingle = Counter.builder("urlcache.invalidations.received").tag("scope", "single").register(registry);
        this.invReceivedAll = Counter.builder("urlcache.invalidations.received").tag("scope", "all").register(registry);
        // 대시보드에서 현재 모드(레이어드=1 / Redis 단독=0)를 시계열로 확인할 수 있게 게이지로 노출.
        registry.gauge("urlcache.l1.enabled", this.l1Enabled, b -> b.get() ? 1.0 : 0.0);
        // 무효화 전파 상태(적용=1 / 미적용=0)도 게이지로 노출 → 검증 대시보드에서 on/off 구간 구분.
        registry.gauge("urlcache.invalidation.propagation.enabled", this.propagationEnabled, b -> b.get() ? 1.0 : 0.0);
    }

    public Optional<UrlView> get(String shortCode, Supplier<Optional<UrlView>> loader) {
        boolean useL1 = l1Enabled.get();
        if (useL1) {
            l1Requests.increment();
            UrlView fromL1 = l1.getIfPresent(shortCode);
            if (fromL1 != null) {
                l1Hits.increment();
                return Optional.of(fromL1);
            }
        }

        l2Requests.increment();
        UrlView fromL2 = l2.opsForValue().get(KEY_PREFIX + shortCode);
        if (fromL2 != null) {
            l2Hits.increment();
            if (useL1) {
                l1.put(shortCode, fromL2);
            }
            return Optional.of(fromL2);
        }

        return singleFlight.execute(shortCode, () -> {
            // 대기 중 다른 스레드가 채웠을 수 있으므로 L1 재확인.
            if (l1Enabled.get()) {
                l1Requests.increment();
                UrlView racedL1 = l1.getIfPresent(shortCode);
                if (racedL1 != null) {
                    l1Hits.increment();
                    return Optional.of(racedL1);
                }
            }
            Optional<UrlView> loaded = loader.get();
            dbLoads.increment();
            loaded.ifPresent(view -> {
                l2.opsForValue().set(KEY_PREFIX + shortCode, view, l2Ttl);
                if (l1Enabled.get()) {
                    l1.put(shortCode, view);
                }
            });
            return loaded;
        });
    }

    /**
     * 생성 직후 조회를 캐시 히트로 흡수하기 위한 L2(Redis) 선입력(Write-Through).
     *
     * <p>URL 생성 시점에 값을 미리 심어, 생성 직후 조회가 Replication Lag 구간의 Replica로 내려가
     * 404를 반환하는 것을 차단한다. TTL은 짧게(prime-ttl) 잡아 미조회 키의 캐시 오염을 막는다.
     * L1은 인스턴스 로컬이라 다른 인스턴스에는 도움이 되지 않으므로, 공유 캐시인 L2에만 심는다.
     */
    public void prime(String shortCode, UrlView view) {
        l2.opsForValue().set(KEY_PREFIX + shortCode, view, primeTtl);
    }

    /** L1 사용 여부 조회. */
    public boolean isL1Enabled() {
        return l1Enabled.get();
    }

    /**
     * L1 런타임 토글. 끌 때는 남아 있는 로컬 캐시가 다음 벤치에 영향을 주지 않도록 즉시 비운다.
     * 벤치마크에서 "Redis 단독" ↔ "레이어드"를 재시작 없이 전환하는 용도.
     */
    public void setL1Enabled(boolean enabled) {
        l1Enabled.set(enabled);
        if (!enabled) {
            l1.invalidateAll();
        }
    }

    /** 단일 키 즉시 무효화: 로컬·Redis 제거 후 전 인스턴스에 무효화 브로드캐스트. */
    public void invalidate(String shortCode) {
        l1.invalidate(shortCode);
        l2.delete(KEY_PREFIX + shortCode);
        publisher.convertAndSend(RedisConfig.INVALIDATION_CHANNEL, shortCode);
        invPublishedSingle.increment();
    }

    /** 전체 무효화: 만료 정리 등 대량 변경 시 사용. */
    public void invalidateAll() {
        l1.invalidateAll();
        Set<String> keys = l2.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            l2.delete(keys);
        }
        publisher.convertAndSend(RedisConfig.INVALIDATION_CHANNEL, CacheInvalidationListener.INVALIDATE_ALL);
        invPublishedAll.increment();
    }

    /** 무효화 전파 적용 여부 조회. */
    public boolean isPropagationEnabled() {
        return propagationEnabled.get();
    }

    /**
     * 무효화 전파 토글(검증용 음성대조군). 끄면 이후 수신 메시지에 대해 L1을 비우지 않아
     * "Pub/Sub가 없거나 메시지를 놓친 인스턴스"를 재현한다.
     */
    public void setPropagationEnabled(boolean enabled) {
        propagationEnabled.set(enabled);
    }

    /**
     * Pub/Sub 수신 시 로컬(L1)만 비운다. L2는 발행 인스턴스가 이미 제거했다.
     * 전파가 꺼져 있으면(음성대조군) 아무것도 하지 않아 L1에 Stale이 남는다 → 수신 카운터도 올리지 않으므로
     * Grafana에서 '발행 vs 수신' 갭으로 미전파가 드러난다.
     */
    void evictLocal(String shortCode) {
        if (!propagationEnabled.get()) {
            return;
        }
        l1.invalidate(shortCode);
        invReceivedSingle.increment();
    }

    void evictLocalAll() {
        if (!propagationEnabled.get()) {
            return;
        }
        l1.invalidateAll();
        invReceivedAll.increment();
    }
}
