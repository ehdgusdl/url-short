package com.example.urlshort.id;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SnowflakeIdGenerator 단위 테스트")
class SnowflakeIdGeneratorTest {

    @Test
    @DisplayName("nextId_monotonically_increasing: 1만 회 호출 시 항상 단조 증가")
    void nextId_monotonically_increasing() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0, 0);
        long prev = generator.nextId();
        for (int i = 0; i < 9_999; i++) {
            long next = generator.nextId();
            assertThat(next).isGreaterThan(prev);
            prev = next;
        }
    }

    @Test
    @DisplayName("nextId_all_unique: 1만 회 호출 결과 모두 unique")
    void nextId_all_unique() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0, 0);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(generator.nextId());
        }
        assertThat(ids).hasSize(10_000);
    }

    @Test
    @DisplayName("constructor_invalid_datacenterId_negative: -1이면 IllegalArgumentException")
    void constructor_invalid_datacenterId_negative() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor_invalid_datacenterId_overflow: 32이면 IllegalArgumentException")
    void constructor_invalid_datacenterId_overflow() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(32, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor_invalid_workerId_negative: -1이면 IllegalArgumentException")
    void constructor_invalid_workerId_negative() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor_invalid_workerId_overflow: 32이면 IllegalArgumentException")
    void constructor_invalid_workerId_overflow() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(0, 32))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("multithreaded_all_unique: 8스레드 x 1000회 = 8000개 모두 unique")
    void multithreaded_all_unique() throws Exception {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        int threads = 8;
        int callsPerThread = 1000;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<List<Long>>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                List<Long> ids = new ArrayList<>(callsPerThread);
                for (int i = 0; i < callsPerThread; i++) {
                    ids.add(generator.nextId());
                }
                return ids;
            });
        }

        List<Future<List<Long>>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        Set<Long> allIds = new HashSet<>();
        for (Future<List<Long>> future : futures) {
            allIds.addAll(future.get());
        }

        assertThat(allIds).hasSize(threads * callsPerThread);
    }
}
