package com.example.urlshort.config;

import com.example.urlshort.dto.UrlView;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * L1(로컬, Caffeine) 캐시 구성.
 *
 * <p>로컬 캐시로 네트워크 I/O 없이 리다이렉트 응답 속도를 확보하고, 서버 간 일관성은
 * Pub/Sub 기반 즉시 무효화가 책임진다. expireAfterWrite는 무효화 메시지 유실에 대비한 안전장치다.
 */
@Configuration
public class CacheConfig {

    @Bean
    public Cache<String, UrlView> localUrlCache(
            @Value("${app.cache.local-max-size:10000}") long maxSize,
            @Value("${app.cache.local-ttl:5m}") Duration ttl) {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl)
                .build();
    }
}
