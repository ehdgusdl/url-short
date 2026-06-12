package com.example.urlshort.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SingleFlight 단위 테스트")
class SingleFlightTest {

    @Test
    @DisplayName("같은 키로 동시 요청이 몰리면 로더는 한 번만 수행되고 결과는 공유된다")
    void deduplicates_concurrent_loads_for_same_key() throws Exception {
        SingleFlight singleFlight = new SingleFlight();
        AtomicInteger loaderCalls = new AtomicInteger();
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<String>[] futures = new Future[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return singleFlight.execute("same-key", () -> {
                        loaderCalls.incrementAndGet();
                        sleepQuietly(100);
                        return "value";
                    });
                });
            }

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            for (Future<String> f : futures) {
                assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo("value");
            }
            // leader 하나만 로더를 수행해야 한다(follower는 결과 공유).
            assertThat(loaderCalls.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("키가 다르면 각각 로더가 수행된다")
    void different_keys_run_independently() {
        SingleFlight singleFlight = new SingleFlight();
        AtomicInteger loaderCalls = new AtomicInteger();

        singleFlight.execute("a", () -> { loaderCalls.incrementAndGet(); return 1; });
        singleFlight.execute("b", () -> { loaderCalls.incrementAndGet(); return 2; });

        assertThat(loaderCalls.get()).isEqualTo(2);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
