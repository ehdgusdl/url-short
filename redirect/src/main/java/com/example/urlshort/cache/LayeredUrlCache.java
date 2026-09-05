package com.example.urlshort.cache;

import com.example.urlshort.dto.UrlView;
import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final Cache<String, UrlView> l1;
    private final RedisTemplate<String, UrlView> l2;
    private final StringRedisTemplate strings;
    private final SingleFlight singleFlight;
    private final HotKeySet hotKeys;
    private final java.time.Duration l2Ttl;

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

    public LayeredUrlCache(@Qualifier("localUrlCache") Cache<String, UrlView> l1,
                           RedisTemplate<String, UrlView> l2,
                           StringRedisTemplate strings,
                           SingleFlight singleFlight,
                           HotKeySet hotKeys,
                           @Value("${app.cache.l2-ttl:1h}") java.time.Duration l2Ttl,
                           @Value("${app.cache.l1-enabled:true}") boolean l1Enabled,
                           MeterRegistry registry) {
        this.l1 = l1;
        this.l2 = l2;
        this.strings = strings;
        this.singleFlight = singleFlight;
        this.hotKeys = hotKeys;
        this.l2Ttl = l2Ttl;
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

        l2Requests.increment();
        UrlView fromL2 = l2.opsForValue().get(CacheChannels.KEY_PREFIX + shortCode);
        if (fromL2 != null) {
            l2Hits.increment();
            if (useL1) {
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
            Optional<UrlView> loaded = loader.get();
            dbLoads.increment();
            // 방금 삭제된 키인데 Replica가 아직 옛 행을 갖고 있는 경우다. 여기서 캐시에 담으면
            // 무효화 메시지는 이미 지나갔으므로 L2 TTL 내내 삭제된 URL이 계속 302된다.
            if (loaded.isPresent() && isTombstoned(shortCode)) {
                tombstoned.increment();
                return Optional.<UrlView>empty();
            }
            loaded.ifPresent(view -> {
                l2.opsForValue().set(CacheChannels.KEY_PREFIX + shortCode, view, l2Ttl);
                if (useL1) {
                    l1.put(shortCode, view);
                }
            });
            return loaded;
        });
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

    /** 현재 L1 엔트리 수(검증·대시보드용). */
    public long localSize() {
        return l1.estimatedSize();
    }
}
