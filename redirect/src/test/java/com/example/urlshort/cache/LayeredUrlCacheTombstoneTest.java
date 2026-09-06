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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 삭제 직후 복제 지연 구간 보호.
 *
 * <p>Replica가 아직 옛 행을 갖고 있는 동안 그 결과를 캐시에 담으면, 무효화 메시지는 이미 지나갔으므로
 * L2 TTL 내내 삭제된 URL이 계속 302된다.
 */
@DisplayName("LayeredUrlCache 묘비(tombstone) 테스트")
class LayeredUrlCacheTombstoneTest {

    private Cache<String, UrlView> l1;
    private ValueOperations<String, UrlView> ops;
    private StringRedisTemplate strings;
    private SimpleMeterRegistry registry;
    private LayeredUrlCache cache;

    private final UrlView staleFromReplica =
            new UrlView("gone", "https://example.com/deleted", Instant.now().plusSeconds(3600));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        l1 = Caffeine.newBuilder().maximumSize(100).build();
        registry = new SimpleMeterRegistry();

        RedisTemplate<String, UrlView> l2 = Mockito.mock(RedisTemplate.class);
        ops = Mockito.mock(ValueOperations.class);
        Mockito.when(l2.opsForValue()).thenReturn(ops);
        Mockito.when(ops.get(Mockito.anyString())).thenReturn(null); // 삭제됐으므로 L2 미스

        strings = Mockito.mock(StringRedisTemplate.class);
        cache = new LayeredUrlCache(l1, l2, strings, new SingleFlight(),
                new HotKeySet(registry), Duration.ofHours(1), Duration.ZERO, true, registry);
    }

    @Test
    @DisplayName("묘비가 있으면 Replica가 준 옛 행을 버리고 캐시에 담지 않는다")
    void discards_stale_row_during_replication_lag() {
        Mockito.when(strings.hasKey(CacheChannels.TOMBSTONE_PREFIX + "gone")).thenReturn(true);

        Optional<UrlView> result = cache.get("gone", () -> Optional.of(staleFromReplica));

        assertThat(result).isEmpty();
        assertThat(l1.getIfPresent("gone")).isNull();
        Mockito.verify(ops, Mockito.never()).set(Mockito.anyString(), Mockito.any(), Mockito.any(Duration.class));
        assertThat(registry.get("urlcache.tombstoned").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("묘비가 없으면 평소대로 캐시에 담는다")
    void caches_normally_without_tombstone() {
        Mockito.when(strings.hasKey(Mockito.anyString())).thenReturn(false);

        Optional<UrlView> result = cache.get("gone", () -> Optional.of(staleFromReplica));

        assertThat(result).isPresent();
        assertThat(l1.getIfPresent("gone")).isNotNull();
        Mockito.verify(ops).set(Mockito.eq("url:gone"), Mockito.any(), Mockito.any(Duration.class));
    }

    @Test
    @DisplayName("Redis 확인이 실패해도 조회는 계속된다")
    void survives_redis_failure() {
        Mockito.when(strings.hasKey(Mockito.anyString()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("redis down"));

        Optional<UrlView> result = cache.get("gone", () -> Optional.of(staleFromReplica));

        assertThat(result).isPresent();
    }
}
