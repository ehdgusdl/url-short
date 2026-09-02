package com.example.urlshort.service;

import com.example.urlshort.cache.LayeredUrlCache;
import com.example.urlshort.repository.UrlMappingRepository;
import com.example.urlshort.repository.UrlReadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ExpiredUrlCleaner {

    private static final Logger log = LoggerFactory.getLogger(ExpiredUrlCleaner.class);

    private final UrlMappingRepository repository;
    private final UrlReadRepository readRepository;
    private final LayeredUrlCache cache;

    public ExpiredUrlCleaner(UrlMappingRepository repository, UrlReadRepository readRepository, LayeredUrlCache cache) {
        this.repository = repository;
        this.readRepository = readRepository;
        this.cache = cache;
    }

    @Scheduled(cron = "${app.url.cleanup-cron:0 0 * * * *}")
    public void cleanup() {
        Instant cutoff = Instant.now();
        readRepository.deleteAllByExpiresAtBefore(cutoff);
        long deleted = repository.deleteAllByExpiresAtBefore(cutoff);
        log.info("Expired URL cleanup: deleted {} record(s)", deleted);
        // 만료 정리 후 전 인스턴스의 L1/L2 캐시를 비워 만료분의 Stale 응답을 차단한다.
        cache.invalidateAll();
    }
}
