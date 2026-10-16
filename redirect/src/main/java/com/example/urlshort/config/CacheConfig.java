package com.example.urlshort.config;

import com.example.urlshort.dto.UrlView;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * L1(로컬, Caffeine) 캐시 구성.
 *
 * <p>로컬 캐시로 네트워크 I/O 없이 리다이렉트 응답 속도를 확보하고, 서버 간 일관성은
 * Pub/Sub 기반 즉시 무효화가 책임진다. expireAfterWrite는 무효화 메시지 유실에 대비한 안전장치다.
 *
 * <p>상한은 건수(local-max-size)와 힙 예산(heap-budget-bytes) 중 작은 쪽이 정한다.
 * 건수만으로 잡으면 엔트리 크기를 곱해봐야 예산을 넘는지 알 수 없고, 실제로 넘겼다.
 */
@Configuration
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public Cache<String, UrlView> localUrlCache(
            @Value("${app.cache.local-max-size:10000}") long maxSize,
            @Value("${app.cache.heap-budget-bytes:107374182}") long heapBudgetBytes,
            @Value("${app.cache.bytes-per-entry:576}") long bytesPerEntry,
            @Value("${app.cache.local-ttl:5m}") Duration ttl) {
        long byBudget = heapBudgetBytes / bytesPerEntry;
        long effective = Math.min(maxSize, byBudget);
        if (effective < maxSize) {
            log.info("L1 max size capped by heap budget: {} -> {} entries ({} bytes / {} B per entry)",
                    maxSize, effective, heapBudgetBytes, bytesPerEntry);
        }
        return Caffeine.newBuilder()
                .maximumSize(effective)
                .expireAfterWrite(ttl)
                .build();
    }

    /**
     * L2(Redis) 호출 차단기. 커맨드 타임아웃만 있으면 장애 내내 L1 미스마다 타임아웃만큼 기다린다.
     * 최근 50회 중 절반이 실패하면 열고, 5초 뒤 5회만 시험해 닫을지 정한다.
     * 느린 호출 기준은 두지 않는다. 커맨드 타임아웃(100ms)이 느린 Redis를 이미 실패로 바꾸고,
     * 앱 쪽에서 재는 시간에는 CPU 포화·GC 정지가 섞여 Redis가 멀쩡해도 차단기가 열릴 수 있다.
     */
    @Bean
    public CircuitBreaker l2CircuitBreaker(MeterRegistry registry) {
        CircuitBreaker breaker = CircuitBreaker.of("l2", CircuitBreakerConfig.custom()
                .slidingWindowSize(50)
                .minimumNumberOfCalls(20)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(5)
                .build());
        // 상태 전이만 남긴다. 0=CLOSED, 1=OPEN, 2=HALF_OPEN
        breaker.getEventPublisher().onStateTransition(e -> log.warn("L2 circuit breaker {}", e.getStateTransition()));
        registry.gauge("urlcache.l2.breaker.state", breaker, b -> switch (b.getState()) {
            case OPEN, FORCED_OPEN -> 1.0;
            case HALF_OPEN -> 2.0;
            default -> 0.0;
        });
        return breaker;
    }
}
