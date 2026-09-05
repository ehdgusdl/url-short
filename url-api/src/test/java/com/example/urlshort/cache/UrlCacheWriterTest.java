package com.example.urlshort.cache;

import com.example.urlshort.dto.UrlView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

@DisplayName("UrlCacheWriter 단위 테스트")
class UrlCacheWriterTest {

    private ValueOperations<String, String> stringOps;
    private RedisTemplate<String, UrlView> l2;
    private StringRedisTemplate publisher;
    private UrlCacheWriter writer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        l2 = Mockito.mock(RedisTemplate.class);
        Mockito.when(l2.opsForValue()).thenReturn(Mockito.mock(ValueOperations.class));
        publisher = Mockito.mock(StringRedisTemplate.class);
        stringOps = Mockito.mock(ValueOperations.class);
        Mockito.when(publisher.opsForValue()).thenReturn(stringOps);

        writer = new UrlCacheWriter(l2, publisher, Duration.ofSeconds(10), Duration.ofSeconds(10),
                true, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("삭제 시 묘비를 남긴다 — 복제 지연 구간에 Replica의 옛 행이 되살아나는 것을 막는다")
    void invalidate_writes_tombstone() {
        writer.invalidate("aB3xK9p");

        Mockito.verify(stringOps).set("url:gone:aB3xK9p", "1", Duration.ofSeconds(10));
        Mockito.verify(l2).delete("url:aB3xK9p");
        Mockito.verify(publisher).convertAndSend(CacheChannels.INVALIDATION_CHANNEL, "aB3xK9p");
    }
}
