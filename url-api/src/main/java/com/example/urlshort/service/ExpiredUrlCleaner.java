package com.example.urlshort.service;

import com.example.urlshort.cache.UrlCacheWriter;
import com.example.urlshort.repository.UrlMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ExpiredUrlCleaner {

    private static final Logger log = LoggerFactory.getLogger(ExpiredUrlCleaner.class);

    private final UrlMappingRepository repository;
    private final UrlCacheWriter cache;

    public ExpiredUrlCleaner(UrlMappingRepository repository, UrlCacheWriter cache) {
        this.repository = repository;
        this.cache = cache;
    }

    @Scheduled(cron = "${app.url.cleanup-cron:0 0 * * * *}")
    public void cleanup() {
        long deleted = repository.deleteAllByExpiresAtBefore(Instant.now());
        log.info("Expired URL cleanup: deleted {} record(s)", deleted);
        // 지운 게 있을 때만 비운다. 0건에도 비우면 매 주기마다 전 인스턴스의 L1/L2가 통째로 날아간다.
        if (deleted > 0) {
            // 만료 정리 후 전 인스턴스의 L2 캐시와 각 redirect의 L1을 비워 만료분의 Stale 응답을 차단한다.
            cache.invalidateAll();
        }
    }
}
