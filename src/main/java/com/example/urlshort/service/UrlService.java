package com.example.urlshort.service;

import com.example.urlshort.cache.LayeredUrlCache;
import com.example.urlshort.cache.RecentWriteTracker;
import com.example.urlshort.config.UrlProperties;
import com.example.urlshort.config.routing.DataSourceContextHolder;
import com.example.urlshort.config.routing.DataSourceType;
import com.example.urlshort.domain.UrlMapping;
import com.example.urlshort.dto.UrlView;
import com.example.urlshort.id.SnowflakeIdGenerator;
import com.example.urlshort.repository.UrlMappingRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
public class UrlService {

    private static final int MAX_RETRIES = 5;

    private final UrlMappingRepository repository;
    private final Base62Generator generator;
    private final SnowflakeIdGenerator snowflake;
    private final UrlProperties props;
    private final LayeredUrlCache cache;
    private final RecentWriteTracker recentWrites;

    public UrlService(UrlMappingRepository repository, Base62Generator generator,
                      SnowflakeIdGenerator snowflake, UrlProperties props,
                      LayeredUrlCache cache, RecentWriteTracker recentWrites) {
        this.repository = repository;
        this.generator = generator;
        this.snowflake = snowflake;
        this.props = props;
        this.cache = cache;
        this.recentWrites = recentWrites;
    }

    public UrlMapping create(String originalUrl) {
        Instant expiresAt = Instant.now().plus(props.ttlDays(), ChronoUnit.DAYS);
        // 유일성 검증과 저장은 Replication Lag의 영향을 받지 않도록 Primary로 고정한다.
        DataSourceContextHolder.set(DataSourceType.PRIMARY);
        try {
            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                String shortCode = generator.generate(Base62Generator.DEFAULT_LENGTH);
                if (!repository.existsByShortCode(shortCode)) {
                    long id = snowflake.nextId();
                    UrlMapping saved = repository.save(UrlMapping.builder()
                            .id(id)
                            .shortCode(shortCode)
                            .originalUrl(originalUrl)
                            .expiresAt(expiresAt)
                            .build());
                    // 생성 직후 짧은 TTL 동안 Primary Fallback 대상으로 표시 → 생성 직후 404 차단.
                    recentWrites.mark(saved.getShortCode());
                    return saved;
                }
            }
            throw new IllegalStateException("failed to generate unique short code");
        } finally {
            DataSourceContextHolder.clear();
        }
    }

    public Optional<UrlView> find(String shortCode) {
        return cache.get(shortCode, () -> loadFromDb(shortCode));
    }

    public boolean delete(String shortCode) {
        DataSourceContextHolder.set(DataSourceType.PRIMARY);
        try {
            long removed = repository.deleteByShortCode(shortCode);
            // 삭제 즉시 전 인스턴스 캐시 무효화(L1/L2 + Pub/Sub) → Stale 리다이렉트 차단.
            cache.invalidate(shortCode);
            return removed > 0;
        } finally {
            DataSourceContextHolder.clear();
        }
    }

    private Optional<UrlView> loadFromDb(String shortCode) {
        // 최근 Write 키는 Replication Lag 구간이므로 Primary, 그 외에는 Replica로 부하를 분산한다.
        DataSourceType target = recentWrites.isRecent(shortCode)
                ? DataSourceType.PRIMARY
                : DataSourceType.REPLICA;
        DataSourceContextHolder.set(target);
        try {
            return repository.findByShortCode(shortCode).map(UrlView::from);
        } finally {
            DataSourceContextHolder.clear();
        }
    }
}
