package com.example.urlshort.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 동일 키에 대한 동시 적재(Cache Miss) 요청을 단일 실행으로 통합하는 Single-flight 유틸리티.
 *
 * <p>같은 shortCode로 캐시 미스가 몰릴 때 첫 요청(leader)만 실제 로더를 수행하고, 나머지(follower)는
 * 그 결과를 공유받아 DB 중복 조회를 방지한다. 인스턴스 단위로 동작한다.
 */
@Component
public class SingleFlight {

    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T execute(String key, Supplier<T> loader) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> running = inFlight.putIfAbsent(key, mine);

        if (running != null) {
            // follower: leader의 결과를 공유받는다(leader 예외 시 동일 예외 전파).
            return (T) running.join();
        }

        // leader: 실제 로더 수행 후 결과를 공유.
        try {
            T result = loader.get();
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }
}
