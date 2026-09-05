package com.example.urlshort.service;

import com.example.urlshort.cache.LayeredUrlCache;
import com.example.urlshort.config.routing.DataSourceContextHolder;
import com.example.urlshort.config.routing.DataSourceType;
import com.example.urlshort.dto.UrlView;
import com.example.urlshort.repository.UrlMappingRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 리다이렉트 읽기 경로. 캐시 미스만 Replica DB로 내려간다. */
@Service
public class RedirectService {

    private final UrlMappingRepository repository;
    private final LayeredUrlCache cache;

    // 캐시 미스 시 Replica로 내려간 실제 DB 조회 수.
    private final Counter replicaReads;

    public RedirectService(UrlMappingRepository repository, LayeredUrlCache cache, MeterRegistry registry) {
        this.repository = repository;
        this.cache = cache;
        this.replicaReads = Counter.builder("urlcache.db.reads").tag("datasource", "replica").register(registry);
    }

    public Optional<UrlView> find(String shortCode) {
        return cache.get(shortCode, () -> loadFromDb(shortCode));
    }

    private Optional<UrlView> loadFromDb(String shortCode) {
        DataSourceContextHolder.set(DataSourceType.REPLICA);
        replicaReads.increment();
        try {
            return repository.findByShortCode(shortCode).map(UrlView::from);
        } finally {
            DataSourceContextHolder.clear();
        }
    }
}
