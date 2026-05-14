package com.example.urlshort.service;

import com.example.urlshort.repository.UrlMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ExpiredUrlCleaner {

    private static final Logger log = LoggerFactory.getLogger(ExpiredUrlCleaner.class);

    private final UrlMappingRepository repository;
    private final CacheManager cacheManager;

    public ExpiredUrlCleaner(UrlMappingRepository repository, CacheManager cacheManager) {
        this.repository = repository;
        this.cacheManager = cacheManager;
    }

    @Scheduled(cron = "${app.url.cleanup-cron:0 0 * * * *}")
    public void cleanup() {
        long deleted = repository.deleteAllByExpiresAtBefore(Instant.now());
        log.info("Expired URL cleanup: deleted {} record(s)", deleted);
        var cache = cacheManager.getCache("url");
        if (cache != null) {
            cache.clear();
        }
    }
}
