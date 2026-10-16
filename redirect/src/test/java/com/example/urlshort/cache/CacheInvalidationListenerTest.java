package com.example.urlshort.cache;

import com.example.urlshort.dto.UrlView;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CacheInvalidationListener 테스트")
class CacheInvalidationListenerTest {

    @Test
    @DisplayName("구독이 다시 붙으면 L1을 비운다 (끊긴 동안 놓친 삭제를 알 수 없다)")
    @SuppressWarnings("unchecked")
    void clears_l1_when_the_subscription_comes_back() {
        Cache<String, UrlView> l1 = Caffeine.newBuilder().maximumSize(100).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisTemplate<String, UrlView> l2 = Mockito.mock(RedisTemplate.class);
        ValueOperations<String, UrlView> ops = Mockito.mock(ValueOperations.class);
        Mockito.when(l2.opsForValue()).thenReturn(ops);
        UrlView view = new UrlView("abc", "https://example.com", Instant.now().plusSeconds(3600));
        Mockito.when(ops.get(Mockito.anyString())).thenReturn(view);
        LayeredUrlCache cache = new LayeredUrlCache(l1, l2, Mockito.mock(StringRedisTemplate.class),
                new SingleFlight(), new HotKeySet(Duration.ofMinutes(2), registry), CircuitBreaker.ofDefaults("l2"),
                Duration.ofHours(1), Duration.ZERO, true, registry);
        cache.get("abc", Optional::empty);
        assertThat(l1.getIfPresent("abc")).isNotNull();

        new CacheInvalidationListener(cache)
                .onChannelSubscribed(CacheChannels.INVALIDATION_CHANNEL.getBytes(StandardCharsets.UTF_8), 1);

        assertThat(l1.getIfPresent("abc")).isNull();
        assertThat(registry.get("urlcache.invalidations.received").tag("scope", "resubscribe").counter().count())
                .isEqualTo(1);
    }
}
