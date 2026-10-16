package com.example.urlshort.service;

import com.example.urlshort.cache.LayeredUrlCache;
import com.example.urlshort.domain.UrlMapping;
import com.example.urlshort.dto.UrlView;
import com.example.urlshort.repository.UrlMappingRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RedirectService DB 격리 테스트")
class RedirectServiceTest {

    @Test
    @DisplayName("DB 동시 조회가 상한에 차면 기다리지 않고 바로 거절한다")
    @SuppressWarnings("unchecked")
    void rejects_loads_beyond_the_bulkhead() throws Exception {
        // 차단기가 열리면 L1 미스가 전부 DB로 몰린다. 풀이 차면 Hikari는 30초까지 스레드를 붙잡는다.
        UrlMappingRepository repository = Mockito.mock(UrlMappingRepository.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.when(repository.findByShortCode(Mockito.anyString())).thenAnswer(inv -> {
            entered.countDown();
            release.await();
            return Optional.<UrlMapping>empty();
        });
        LayeredUrlCache cache = Mockito.mock(LayeredUrlCache.class);
        Mockito.when(cache.get(Mockito.anyString(), Mockito.any()))
                .thenAnswer(inv -> ((Supplier<Optional<UrlView>>) inv.getArgument(1)).get());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedirectService service = new RedirectService(repository, cache, 1, Duration.ofMillis(10), registry);

        CompletableFuture<Optional<UrlView>> first = CompletableFuture.supplyAsync(() -> service.find("aaaaaaa"));
        entered.await();

        assertThatThrownBy(() -> service.find("bbbbbbb")).isInstanceOf(DbBusyException.class);
        assertThat(registry.get("urlcache.db.rejected").counter().count()).isEqualTo(1);
        release.countDown();
        assertThat(first.get()).isEmpty();
    }
}
