package com.example.urlshort.service;

import com.example.urlshort.cache.LayeredUrlCache;
import com.example.urlshort.repository.UrlMappingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    LayeredUrlCache cache;

    @InjectMocks
    ExpiredUrlCleaner cleaner;

    @Test
    @DisplayName("cleanup: deleteAllByExpiresAtBefore가 now 이전 시각으로 호출됨")
    void cleanup_calls_delete_with_now() {
        when(repository.deleteAllByExpiresAtBefore(any(Instant.class))).thenReturn(3L);

        Instant before = Instant.now();
        cleaner.cleanup();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteAllByExpiresAtBefore(captor.capture());
        Instant cutoff = captor.getValue();
        assertThat(cutoff).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("cleanup: 캐시 전체 무효화가 호출됨")
    void cleanup_invalidates_cache() {
        when(repository.deleteAllByExpiresAtBefore(any(Instant.class))).thenReturn(0L);

        cleaner.cleanup();

        verify(cache).invalidateAll();
    }
}
