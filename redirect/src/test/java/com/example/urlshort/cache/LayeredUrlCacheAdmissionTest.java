package com.example.urlshort.cache;

import com.example.urlshort.dto.UrlView;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LayeredUrlCache 적재 제한(admission) 테스트")
class LayeredUrlCacheAdmissionTest {

    private Cache<String, UrlView> l1;
    private HotKeySet hotKeys;
    private SimpleMeterRegistry registry;
    private LayeredUrlCache cache;

    private final UrlView view = new UrlView("k", "https://example.com", Instant.now().plusSeconds(3600));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        l1 = Caffeine.newBuilder().maximumSize(100).build();
        registry = new SimpleMeterRegistry();
        hotKeys = new HotKeySet(registry);

        RedisTemplate<String, UrlView> l2 = Mockito.mock(RedisTemplate.class);
        ValueOperations<String, UrlView> ops = Mockito.mock(ValueOperations.class);
        Mockito.when(l2.opsForValue()).thenReturn(ops);
        Mockito.when(ops.get(Mockito.anyString())).thenReturn(null); // 항상 L2 미스 → 로더까지 내려간다

        StringRedisTemplate strings = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(strings.hasKey(Mockito.anyString())).thenReturn(false); // 묘비 없음

        cache = new LayeredUrlCache(l1, l2, strings, new SingleFlight(), hotKeys,
                Duration.ofHours(1), true, registry);
    }

    private double admission(String result) {
        return registry.get("urlcache.admission").tag("result", result).counter().count();
    }

    @Test
    @DisplayName("핫키가 아니면 L1에 적재하지 않는다")
    void does_not_populate_l1_for_cold_key() {
        hotKeys.replace(1, Set.of("hot"));

        Optional<UrlView> result = cache.get("cold", () -> Optional.of(view));

        assertThat(result).isPresent();
        assertThat(l1.getIfPresent("cold")).isNull();
        assertThat(cache.localSize()).isZero();
    }

    @Test
    @DisplayName("핫키면 L1에 적재한다")
    void populates_l1_for_hot_key() {
        hotKeys.replace(1, Set.of("hot"));

        cache.get("hot", () -> Optional.of(view));

        assertThat(l1.getIfPresent("hot")).isNotNull();
    }

    @Test
    @DisplayName("요청 1건은 admission 판정을 정확히 1회만 기록한다 (미스 경로에서 재판정 금지)")
    void counts_admission_once_per_request() {
        hotKeys.replace(1, Set.of("hot"));

        cache.get("cold", () -> Optional.of(view));

        assertThat(admission("rejected")).isEqualTo(1.0);
        assertThat(admission("admitted")).isZero();
    }
}
