package com.example.urlshort.service;

import com.example.urlshort.cache.UrlCacheWriter;
import com.example.urlshort.config.UrlProperties;
import com.example.urlshort.config.routing.DataSourceContextHolder;
import com.example.urlshort.config.routing.DataSourceType;
import com.example.urlshort.domain.UrlMapping;
import com.example.urlshort.dto.UrlView;
import com.example.urlshort.id.SnowflakeIdGenerator;
import com.example.urlshort.repository.UrlMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class UrlService {

    private static final Logger log = LoggerFactory.getLogger(UrlService.class);
    private static final int MAX_RETRIES = 5;

    private final UrlMappingRepository repository;
    private final Base62Generator generator;
    private final SnowflakeIdGenerator snowflake;
    private final UrlProperties props;
    private final UrlCacheWriter cache;

    public UrlService(UrlMappingRepository repository, Base62Generator generator,
                      SnowflakeIdGenerator snowflake, UrlProperties props,
                      UrlCacheWriter cache) {
        this.repository = repository;
        this.generator = generator;
        this.snowflake = snowflake;
        this.props = props;
        this.cache = cache;
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
                    // 생성 직후 조회를 Replica(Replication Lag 구간) 대신 캐시 히트로 흡수하기 위해 L2에 선입력.
                    // best-effort: 캐시 쓰기 실패가 생성 트랜잭션에 전이되지 않도록 예외를 삼킨다.
                    try {
                        cache.prime(saved.getShortCode(), UrlView.from(saved));
                    } catch (RuntimeException e) {
                        log.warn("cache prime failed for {} (best-effort, ignored)", saved.getShortCode(), e);
                    }
                    return saved;
                }
            }
            throw new IllegalStateException("failed to generate unique short code");
        } finally {
            DataSourceContextHolder.clear();
        }
    }

    /** 목록 조회는 Replica로 내려 읽기 부하를 분산한다. */
    public List<UrlView> list() {
        DataSourceContextHolder.set(DataSourceType.REPLICA);
        try {
            return repository.findTop50ByOrderByCreatedAtDesc().stream().map(UrlView::from).toList();
        } finally {
            DataSourceContextHolder.clear();
        }
    }

    public boolean delete(String shortCode) {
        DataSourceContextHolder.set(DataSourceType.PRIMARY);
        try {
            long removed = repository.deleteByShortCode(shortCode);
            // 삭제 즉시 전 redirect 인스턴스 캐시 무효화(L2 + Pub/Sub) + 묘비 표시.
            // 묘비는 복제 지연 구간에 Replica의 옛 행이 캐시로 되살아나는 것을 막는다.
            cache.invalidate(shortCode);
            return removed > 0;
        } finally {
            DataSourceContextHolder.clear();
        }
    }
}
