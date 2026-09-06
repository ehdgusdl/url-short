package com.example.urlshort.service;

import com.example.urlshort.cache.UrlCacheWriter;
import com.example.urlshort.config.UrlProperties;
import com.example.urlshort.domain.UrlMapping;
import com.example.urlshort.domain.UrlRead;
import com.example.urlshort.dto.UrlView;
import com.example.urlshort.id.SnowflakeIdGenerator;
import com.example.urlshort.repository.UrlMappingRepository;
import com.example.urlshort.repository.UrlReadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("UrlService 단위 테스트")
@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    @Mock
    UrlMappingRepository repository;

    @Mock
    UrlReadRepository readRepository;

    @Mock
    Base62Generator generator;

    @Mock
    SnowflakeIdGenerator snowflake;

    @Mock
    UrlCacheWriter cache;

    UrlService service;

    @BeforeEach
    void setUp() {
        UrlProperties props = new UrlProperties(7, "0 0 * * * *");
        service = new UrlService(repository, readRepository, generator, snowflake, props, cache);
    }

    @Test
    @DisplayName("create_success: 첫 시도에 충돌 없을 때 save 1회 호출, 반환 엔티티 검증")
    void create_success() {
        when(generator.generate(anyInt())).thenReturn("aB3xK9p");
        when(repository.existsByShortCode("aB3xK9p")).thenReturn(false);
        when(snowflake.nextId()).thenReturn(1234567890L);
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlMapping result = service.create("https://example.com");

        assertThat(result.getShortCode()).isEqualTo("aB3xK9p");
        assertThat(result.getOriginalUrl()).isEqualTo("https://example.com");
        assertThat(result.getId()).isEqualTo(1234567890L);
        verify(repository, times(1)).save(any(UrlMapping.class));
        verify(cache).prime(eq("aB3xK9p"), any(UrlView.class));
        // 읽기 모델(url_read)에도 같이 기록해야 한다.
        verify(readRepository, times(1)).save(any(UrlRead.class));
    }

    @Test
    @DisplayName("create_sets_expiresAt_7_days_from_now: expiresAt이 now+7d에 근접")
    void create_sets_expiresAt_7_days_from_now() {
        when(generator.generate(anyInt())).thenReturn("aB3xK9p");
        when(repository.existsByShortCode("aB3xK9p")).thenReturn(false);
        when(snowflake.nextId()).thenReturn(1234567890L);
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant before = Instant.now().plus(7, ChronoUnit.DAYS).minusSeconds(1);
        UrlMapping result = service.create("https://example.com");
        Instant after = Instant.now().plus(7, ChronoUnit.DAYS).plusSeconds(1);

        assertThat(result.getExpiresAt()).isAfter(before).isBefore(after);
    }

    @Test
    @DisplayName("create_retries_on_collision: 첫 2번 충돌 후 3번째 성공 → generator.generate 3회 호출")
    void create_retries_on_collision() {
        when(generator.generate(anyInt()))
                .thenReturn("AAAAAAA", "BBBBBBB", "cC3xK9p");
        when(repository.existsByShortCode("AAAAAAA")).thenReturn(true);
        when(repository.existsByShortCode("BBBBBBB")).thenReturn(true);
        when(repository.existsByShortCode("cC3xK9p")).thenReturn(false);
        when(snowflake.nextId()).thenReturn(9999999999L);
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlMapping result = service.create("https://example.com");

        assertThat(result.getShortCode()).isEqualTo("cC3xK9p");
        verify(generator, times(3)).generate(anyInt());
        verify(repository, times(1)).save(any(UrlMapping.class));
    }

    @Test
    @DisplayName("create_throws_after_5_failures: 5번 모두 충돌 → IllegalStateException, save 0회")
    void create_throws_after_5_failures() {
        when(generator.generate(anyInt())).thenReturn("AAAAAAA");
        when(repository.existsByShortCode("AAAAAAA")).thenReturn(true);

        assertThatThrownBy(() -> service.create("https://example.com"))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).save(any(UrlMapping.class));
        verify(readRepository, never()).save(any(UrlRead.class));
        verify(generator, times(5)).generate(anyInt());
    }

    @Test
    @DisplayName("delete_invalidates_cache: 존재하면 true 반환하고 캐시 무효화 호출")
    void delete_invalidates_cache() {
        when(repository.deleteByShortCode("abc")).thenReturn(1L);

        boolean result = service.delete("abc");

        assertThat(result).isTrue();
        verify(readRepository).deleteByShortCode("abc");
        verify(cache).invalidate("abc");
    }

    @Test
    @DisplayName("delete_returns_false_when_absent: 없으면 false, 무효화도 하지 않는다")
    void delete_returns_false_when_absent() {
        // 지운 게 없는데 묘비를 쓰면 존재하지 않는 코드로 Redis 키가 요청 수만큼 늘어난다.
        when(repository.deleteByShortCode("none")).thenReturn(0L);

        boolean result = service.delete("none");

        assertThat(result).isFalse();
        verify(cache, never()).invalidate(anyString());
    }

}
