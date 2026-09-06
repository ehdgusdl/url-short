package com.example.urlshort.service;

import com.example.urlshort.cache.UrlCacheWriter;
import com.example.urlshort.repository.UrlMappingRepository;
import com.example.urlshort.repository.UrlReadRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ExpiredUrlCleaner 단위 테스트")
@ExtendWith(MockitoExtension.class)
class ExpiredUrlCleanerTest {

    @Mock
    UrlMappingRepository repository;

    @Mock
    UrlReadRepository readRepository;

    @Mock
    UrlCacheWriter cache;

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
        // 읽기 모델도 같은 cutoff로 정리되어야 한다.
        verify(readRepository).deleteAllByExpiresAtBefore(cutoff);
    }

    @Test
    @DisplayName("cleanup: 지운 게 있으면 캐시 전체 무효화가 호출됨")
    void cleanup_invalidates_cache_when_something_was_deleted() {
        when(repository.deleteAllByExpiresAtBefore(any(Instant.class))).thenReturn(3L);

        cleaner.cleanup();

        verify(cache).invalidateAll();
    }

    @Test
    @DisplayName("cleanup: 지운 게 없으면 무효화하지 않는다")
    void cleanup_does_not_invalidate_when_nothing_was_deleted() {
        // 0건에도 비우면 매 주기마다 전 인스턴스의 L1/L2가 통째로 날아간다.
        when(repository.deleteAllByExpiresAtBefore(any(Instant.class))).thenReturn(0L);

        cleaner.cleanup();

        verify(cache, never()).invalidateAll();
    }
}
