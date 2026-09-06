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

    @Test
    @DisplayName("leader가 Error로 죽어도 follower가 영원히 대기하지 않는다")
    void follower_is_released_when_the_leader_dies_with_an_error() throws Exception {
        // RuntimeException 만 잡으면 Error 일 때 future 가 완료되지 않은 채 버려지고,
        // join() 에는 타임아웃이 없어 대기 중인 워커가 그대로 소진된다.
        SingleFlight singleFlight = new SingleFlight();
        CountDownLatch leaderInside = new CountDownLatch(1);
        CountDownLatch releaseLeader = new CountDownLatch(1);

        Thread leader = new Thread(() -> {
            try {
                singleFlight.execute("k", () -> {
                    leaderInside.countDown();
                    await(releaseLeader);
                    throw new StackOverflowError("boom");
                });
            } catch (Throwable ignored) {
                // leader 는 죽는 게 정상이다
            }
        });
        leader.start();
        assertThat(leaderInside.await(2, TimeUnit.SECONDS)).isTrue();

        CountDownLatch followerDone = new CountDownLatch(1);
        Thread follower = new Thread(() -> {
            try {
                singleFlight.execute("k", () -> "never");
            } catch (Throwable ignored) {
                // leader 의 예외가 그대로 전파되는 것도 정상이다
            } finally {
                followerDone.countDown();
            }
        });
        follower.start();
        // follower 가 실제로 leader 의 결과를 기다리는 상태가 될 때까지 기다린다.
        // 그 전에 leader 를 풀면 follower 가 스스로 leader 가 되어 아무것도 검증하지 못한다.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (follower.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(follower.getState()).isEqualTo(Thread.State.WAITING);

        releaseLeader.countDown();

        assertThat(followerDone.await(3, TimeUnit.SECONDS)).isTrue();
        leader.join(1000);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
