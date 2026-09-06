package com.example.urlshort.cache;

import com.example.urlshort.dto.UrlView;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** L2 장애·무효화 경합·음성 캐시. 셋 다 "미스가 어떻게 처리되는가"의 문제다. */
@DisplayName("LayeredUrlCache 미스 경로 테스트")
class LayeredUrlCacheResilienceTest {

    private Cache<String, UrlView> l1;
    private ValueOperations<String, UrlView> ops;
    private StringRedisTemplate strings;
    private ValueOperations<String, String> stringOps;
    private SimpleMeterRegistry registry;

    private final UrlView view =
            new UrlView("abc", "https://example.com", Instant.now().plusSeconds(3600));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        l1 = Caffeine.newBuilder().maximumSize(100).build();
        registry = new SimpleMeterRegistry();
        ops = Mockito.mock(ValueOperations.class);
        strings = Mockito.mock(StringRedisTemplate.class);
        stringOps = Mockito.mock(ValueOperations.class);
        Mockito.when(strings.opsForValue()).thenReturn(stringOps);
    }

    @SuppressWarnings("unchecked")
    private LayeredUrlCache cacheWith(Duration missTtl) {
        RedisTemplate<String, UrlView> l2 = Mockito.mock(RedisTemplate.class);
        Mockito.when(l2.opsForValue()).thenReturn(ops);
        return new LayeredUrlCache(l1, l2, strings, new SingleFlight(),
                new HotKeySet(registry), Duration.ofHours(1), missTtl, true, registry);
    }

    @Test
    @DisplayName("L2가 죽어도 로더로 계속 서빙한다 (L1 미스가 500이 되면 안 된다)")
    void serves_from_the_loader_when_l2_is_down() {
        // L1에 없는 모든 키가 500이 되면, L1 히트는 정상이라 장애가 아니라 '에러율 절반'으로 보인다.
        Mockito.when(ops.get(Mockito.anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        Mockito.doThrow(new RedisConnectionFailureException("redis down"))
                .when(ops).set(Mockito.anyString(), Mockito.any(), Mockito.any(Duration.class));
        LayeredUrlCache cache = cacheWith(Duration.ZERO);

        Optional<UrlView> result = cache.get("abc", () -> Optional.of(view));

        assertThat(result).contains(view);
        assertThat(registry.get("urlcache.l2.errors").counter().count()).isGreaterThan(0);
    }

    @Test
    @DisplayName("적재 직전에 무효화가 들어오면 담지 않는다")
    void does_not_cache_a_value_invalidated_mid_flight() {
        // 검사와 put 사이에 삭제가 끼면 지워진 매핑이 L1에 남고, 무효화 메시지는 이미 지나간 뒤다.
        Mockito.when(ops.get(Mockito.anyString())).thenReturn(null);
        LayeredUrlCache cache = cacheWith(Duration.ZERO);

        Optional<UrlView> result = cache.get("abc", () -> {
            cache.evictLocal("abc");   // 로더가 도는 사이 삭제가 전파됐다
            return Optional.of(view);
        });

        assertThat(result).contains(view);
        assertThat(l1.getIfPresent("abc")).isNull();
    }

    @Test
    @DisplayName("없는 코드는 기억해 두고 다음 요청은 DB까지 내려가지 않는다")
    void remembers_a_miss_and_skips_the_loader_next_time() {
        Mockito.when(ops.get(Mockito.anyString())).thenReturn(null);
        LayeredUrlCache cache = cacheWith(Duration.ofSeconds(30));
        AtomicInteger loaderCalls = new AtomicInteger();

        // 1회차: DB까지 내려가고 미스를 기록한다
        assertThat(cache.get("nope", () -> {
            loaderCalls.incrementAndGet();
            return Optional.<UrlView>empty();
        })).isEmpty();
        Mockito.verify(stringOps).set(CacheChannels.MISS_PREFIX + "nope", "1", Duration.ofSeconds(30));

        // 2회차: 기록이 남아 있으면 로더를 부르지 않는다
        Mockito.when(strings.hasKey(CacheChannels.MISS_PREFIX + "nope")).thenReturn(true);
        assertThat(cache.get("nope", () -> {
            loaderCalls.incrementAndGet();
            return Optional.<UrlView>empty();
        })).isEmpty();

        assertThat(loaderCalls.get()).isEqualTo(1);
        assertThat(registry.get("urlcache.hits").tag("layer", "negative").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("miss-ttl 을 0으로 두면 음성 캐시를 쓰지 않는다")
    void negative_cache_can_be_turned_off() {
        Mockito.when(ops.get(Mockito.anyString())).thenReturn(null);
        LayeredUrlCache cache = cacheWith(Duration.ZERO);

        assertThat(cache.get("nope", Optional::<UrlView>empty)).isEmpty();

        Mockito.verify(stringOps, Mockito.never())
                .set(Mockito.anyString(), Mockito.anyString(), Mockito.any(Duration.class));
    }
}
