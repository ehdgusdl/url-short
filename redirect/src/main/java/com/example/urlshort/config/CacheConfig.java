package com.example.urlshort.config;

import com.example.urlshort.dto.UrlView;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
}
