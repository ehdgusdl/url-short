package com.example.urlshort.cache;

import com.example.urlshort.dto.UrlView;
import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * L1(로컬 Caffeine) + L2(Redis) 2단 캐시의 <b>읽기 경로</b>.
 *
 * <p>조회는 L1 → L2 → 로더(DB) 순으로 폴백하며, 미스 시 {@link SingleFlight}로 동일 키 중복 적재를 막는다.
 * L1 적재는 {@link HotKeySet}이 허용한 키로만 제한한다(admission) — 조건 없는 put이 힙을 URL 총량에
 * 정비례해 밀어 올리던 원인이었다.
 *
 * <p>쓰기(선입력·무효화 발행)는 url-api의 UrlCacheWriter가 소유한다. 여기서는 Pub/Sub로 받은
 * 무효화를 로컬 L1에 적용하기만 한다.
 */
@Component
public class LayeredUrlCache {

    private static final Logger log = LoggerFactory.getLogger(LayeredUrlCache.class);

    private final Cache<String, UrlView> l1;
    private final RedisTemplate<String, UrlView> l2;
    private final StringRedisTemplate strings;
    private final SingleFlight singleFlight;
    private final HotKeySet hotKeys;
    private final java.time.Duration l2Ttl;
    private final java.time.Duration missTtl;

    /**
     * 무효화가 일어날 때마다 증가한다. 조회 시작 시점의 값과 적재 직전 값이 다르면 담지 않는다.
     * 키 단위가 아니라 전역이라, 다른 키의 무효화 때문에 적재 한 번을 건너뛸 수 있다.
     * 삭제는 드물고, 잘못 담아 두는 쪽이 훨씬 비싸므로 이 편이 낫다.
     */
    private final AtomicLong generation = new AtomicLong();

    /** L2 상태. 상태가 바뀌는 순간에만 로그를 남기기 위한 것이다. */
    private final AtomicBoolean l2Healthy = new AtomicBoolean(true);

    /**
     * L1(로컬 Caffeine) 사용 여부. 벤치마크에서 재시작 없이 "Redis 단독" ↔ "레이어드"를 전환하기 위한 런타임 스위치.
     */
    private final AtomicBoolean l1Enabled;

    /**
     * Pub/Sub 무효화 전파 적용 여부. 무효화 검증의 <b>음성대조군</b>이다.
     * 끄면 구독 메시지를 받아도 L1을 비우지 않아 "메시지를 놓친 인스턴스"를 재현한다.
     */
    private final AtomicBoolean propagationEnabled;

    private final Counter l1Requests;
    private final Counter l1Hits;
    private final Counter l2Requests;
    private final Counter l2Hits;
    private final Counter dbLoads;
    private final Counter l1Admitted;   // 핫키라서 L1에 적재한 횟수
    private final Counter l1Rejected;   // 핫키가 아니라 L1을 건너뛴 횟수
    private final Counter invReceivedSingle;
    private final Counter invReceivedAll;
    private final Counter tombstoned;   // 복제 지연 구간이라 되살리지 않고 버린 로더 결과
    private final Counter negativeHits; // 음성 캐시로 DB까지 안 내려간 횟수
    private final Counter l2Errors;     // L2 조회/적재 실패(로더로 흘림)

    public LayeredUrlCache(@Qualifier("localUrlCache") Cache<String, UrlView> l1,
                           RedisTemplate<String, UrlView> l2,
                           StringRedisTemplate strings,
                           SingleFlight singleFlight,
                           HotKeySet hotKeys,
                           @Value("${app.cache.l2-ttl:1h}") java.time.Duration l2Ttl,
                           @Value("${app.cache.miss-ttl:30s}") java.time.Duration missTtl,
                           @Value("${app.cache.l1-enabled:true}") boolean l1Enabled,
                           MeterRegistry registry) {
        this.l1 = l1;
        this.l2 = l2;
        this.strings = strings;
        this.singleFlight = singleFlight;
        this.hotKeys = hotKeys;
        this.l2Ttl = l2Ttl;
        this.missTtl = missTtl;
        this.l1Enabled = new AtomicBoolean(l1Enabled);
        this.propagationEnabled = new AtomicBoolean(true);
        this.l1Requests = Counter.builder("urlcache.requests").tag("layer", "l1").register(registry);
        this.l1Hits = Counter.builder("urlcache.hits").tag("layer", "l1").register(registry);
        this.l2Requests = Counter.builder("urlcache.requests").tag("layer", "l2").register(registry);
        this.l2Hits = Counter.builder("urlcache.hits").tag("layer", "l2").register(registry);
        this.dbLoads = Counter.builder("urlcache.loads").register(registry);
        this.l1Admitted = Counter.builder("urlcache.admission").tag("result", "admitted").register(registry);
        this.l1Rejected = Counter.builder("urlcache.admission").tag("result", "rejected").register(registry);
        this.invReceivedSingle = Counter.builder("urlcache.invalidations.received").tag("scope", "single").register(registry);
        this.invReceivedAll = Counter.builder("urlcache.invalidations.received").tag("scope", "all").register(registry);
        this.tombstoned = Counter.builder("urlcache.tombstoned").register(registry);
        this.negativeHits = Counter.builder("urlcache.hits").tag("layer", "negative").register(registry);
        this.l2Errors = Counter.builder("urlcache.l2.errors").register(registry);
        registry.gauge("urlcache.l1.enabled", this.l1Enabled, b -> b.get() ? 1.0 : 0.0);
        registry.gauge("urlcache.invalidation.propagation.enabled", this.propagationEnabled, b -> b.get() ? 1.0 : 0.0);
        // L1 엔트리 수. 힙 사용량과 나란히 봐야 "무엇이 힙을 먹고 있는가"가 보인다.
        registry.gauge("urlcache.l1.entries", l1, com.github.benmanes.caffeine.cache.Cache::estimatedSize);
    }

    public Optional<UrlView> get(String shortCode, Supplier<Optional<UrlView>> loader) {
        // 적재 여부는 요청당 한 번만 판정한다. 미스 경로에서 다시 판정하면 요청 1건이
        // admission 카운터를 두 번 올려 지표가 부풀려진다.
        boolean useL1 = useL1For(shortCode);
        if (useL1) {
            l1Requests.increment();
            UrlView fromL1 = l1.getIfPresent(shortCode);
            if (fromL1 != null) {
                l1Hits.increment();
                return Optional.of(fromL1);
            }
        }

        // 여기부터 적재까지 사이에 무효화가 들어오면 담지 않는다. 검사와 put 사이에 삭제가 끼면
        // 지워진 매핑이 캐시에 남고, 무효화 메시지는 이미 지나간 뒤라 아무도 지워주지 않는다.
        long generationAtRead = generation.get();

        l2Requests.increment();
        UrlView fromL2 = readL2(shortCode);
        if (fromL2 != null) {
            l2Hits.increment();
            if (useL1 && generationAtRead == generation.get()) {
                l1.put(shortCode, fromL2);
            }
            return Optional.of(fromL2);
        }

        return singleFlight.execute(shortCode, () -> {
            // 대기 중 다른 스레드가 채웠을 수 있으므로 L1 재확인(판정은 위에서 이미 끝났다).
            if (useL1) {
                // 재확인 조회다. requests를 또 올리면 요청 수보다 커져 히트율이 실제보다 낮게 나온다.
                UrlView racedL1 = l1.getIfPresent(shortCode);
                if (racedL1 != null) {
                    l1Hits.increment();
                    return Optional.of(racedL1);
                }
            }
            // 없는 코드로 확인된 지 얼마 안 됐으면 DB까지 내려가지 않는다.
            if (isKnownMiss(shortCode)) {
                negativeHits.increment();
                return Optional.<UrlView>empty();
            }
            Optional<UrlView> loaded = loader.get();
            dbLoads.increment();
            if (loaded.isEmpty()) {
                rememberMiss(shortCode);
                return loaded;
            }
            // 방금 삭제된 키인데 Replica가 아직 옛 행을 갖고 있는 경우다. 여기서 캐시에 담으면
            // 무효화 메시지는 이미 지나갔으므로 L2 TTL 내내 삭제된 URL이 계속 302된다.
            if (isTombstoned(shortCode)) {
                tombstoned.increment();
                return Optional.<UrlView>empty();
            }
            if (generationAtRead == generation.get()) {
                loaded.ifPresent(view -> {
                    writeL2(shortCode, view);
                    if (useL1) {
                        l1.put(shortCode, view);
                    }
                });
            }
            return loaded;
        });
    }

    /**
     * L2 조회. Redis가 죽어도 리다이렉트는 계속돼야 한다.
     *
     * <p>여기서 예외를 그대로 올리면 L1에 없는 모든 키가 500이 된다. MySQL이 멀쩡하고 로더가
     * 답할 수 있는데도다. 게다가 L1 히트는 정상 응답하므로 장애가 아니라 "에러율 절반"으로 보인다.
     */
    private UrlView readL2(String shortCode) {
        try {
            UrlView view = l2.opsForValue().get(CacheChannels.KEY_PREFIX + shortCode);
            markL2Healthy();
            return view;
        } catch (RuntimeException e) {
            markL2Broken(e);
            return null;
        }
    }

    private void writeL2(String shortCode, UrlView view) {
        try {
            l2.opsForValue().set(CacheChannels.KEY_PREFIX + shortCode, view, l2Ttl);
            markL2Healthy();
        } catch (RuntimeException e) {
            markL2Broken(e);
        }
    }

    /** 최근에 "없는 코드"로 확인됐는지. TTL을 0으로 두면 음성 캐시를 끈다. */
    private boolean isKnownMiss(String shortCode) {
        if (missTtl.isZero() || missTtl.isNegative()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(strings.hasKey(CacheChannels.MISS_PREFIX + shortCode));
        } catch (RuntimeException e) {
            markL2Broken(e);
            return false;
        }
    }

    private void rememberMiss(String shortCode) {
        if (missTtl.isZero() || missTtl.isNegative()) {
            return;
        }
        try {
            strings.opsForValue().set(CacheChannels.MISS_PREFIX + shortCode, "1", missTtl);
        } catch (RuntimeException e) {
            markL2Broken(e);
        }
    }

    /** 상태가 바뀔 때만 로그를 남긴다. 매 요청마다 찍으면 Redis 장애 동안 로그가 로그를 덮는다. */
    private void markL2Broken(RuntimeException e) {
        l2Errors.increment();
        if (l2Healthy.compareAndSet(true, false)) {
            log.warn("L2(Redis) unavailable, serving from the loader", e);
        }
    }

    private void markL2Healthy() {
        if (l2Healthy.compareAndSet(false, true)) {
            log.info("L2(Redis) recovered");
        }
    }

    /**
     * 삭제 직후 복제 지연 구간인지. 로더가 값을 찾았을 때만 확인해 Redis 왕복을 최소화한다.
     * 확인 자체가 실패하면 되살리는 쪽보다 버리는 쪽이 안전하다고 보고 삭제로 간주하지 않는다.
     */
    private boolean isTombstoned(String shortCode) {
        try {
            return Boolean.TRUE.equals(strings.hasKey(CacheChannels.TOMBSTONE_PREFIX + shortCode));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 이 키에 L1을 쓸지 판단. L1이 켜져 있고 핫키 목록이 허용해야 한다. */
    private boolean useL1For(String shortCode) {
        if (!l1Enabled.get()) {
            return false;
        }
        if (hotKeys.admits(shortCode)) {
            l1Admitted.increment();
            return true;
        }
        l1Rejected.increment();
        return false;
    }

    public boolean isL1Enabled() {
        return l1Enabled.get();
    }

    /** L1 런타임 토글. 끌 때는 남아 있는 로컬 캐시가 다음 벤치에 영향을 주지 않도록 즉시 비운다. */
    public void setL1Enabled(boolean enabled) {
        l1Enabled.set(enabled);
        if (!enabled) {
            l1.invalidateAll();
        }
    }

    public boolean isPropagationEnabled() {
        return propagationEnabled.get();
    }

    /** 무효화 전파 토글(검증용 음성대조군). */
    public void setPropagationEnabled(boolean enabled) {
        propagationEnabled.set(enabled);
    }

    /** Pub/Sub 수신 시 로컬(L1)만 비운다. L2는 발행 측(url-api)이 이미 제거했다. */
    void evictLocal(String shortCode) {
        if (!propagationEnabled.get()) {
            return;
        }
        generation.incrementAndGet();
        l1.invalidate(shortCode);
        invReceivedSingle.increment();
    }

    void evictLocalAll() {
        if (!propagationEnabled.get()) {
            return;
        }
        generation.incrementAndGet();
        l1.invalidateAll();
        invReceivedAll.increment();
    }

    /** 현재 L1 엔트리 수(검증·대시보드용). */
    public long localSize() {
        return l1.estimatedSize();
    }
}
