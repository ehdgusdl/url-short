package com.example.urlshort.service;

import com.example.urlshort.repository.UrlMappingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ExpiredUrlCleaner 단위 테스트")
@ExtendWith(MockitoExtension.class)
class ExpiredUrlCleanerTest {

    @Mock
    UrlMappingRepository repository;

    @Mock
    CacheManager cacheManager;

    @Mock
    Cache cache;

    @InjectMocks
    ExpiredUrlCleaner cleaner;

    @Test
    @DisplayName("cleanup: deleteAllByExpiresAtBefore가 now 이전 시각으로 호출됨")
    void cleanup_calls_delete_with_now() {
        when(repository.deleteAllByExpiresAtBefore(any(Instant.class))).thenReturn(3L);
        when(cacheManager.getCache("url")).thenReturn(cache);

        Instant before = Instant.now();
        cleaner.cleanup();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteAllByExpiresAtBefore(captor.capture());
        Instant cutoff = captor.getValue();
        assertThat(cutoff).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("cleanup: 캐시 clear가 호출됨")
    void cleanup_clears_cache() {
        when(repository.deleteAllByExpiresAtBefore(any(Instant.class))).thenReturn(0L);
        when(cacheManager.getCache("url")).thenReturn(cache);

        cleaner.cleanup();

        verify(cache).clear();
    }

    @Test
    @DisplayName("cleanup: 캐시가 null이면 NPE 없이 정상 동작")
    void cleanup_handles_null_cache() {
        when(repository.deleteAllByExpiresAtBefore(any(Instant.class))).thenReturn(0L);
        when(cacheManager.getCache("url")).thenReturn(null);

        cleaner.cleanup(); // no exception
    }
}
