package com.example.urlshort.service;

import com.example.urlshort.config.UrlProperties;
import com.example.urlshort.domain.UrlMapping;
import com.example.urlshort.id.SnowflakeIdGenerator;
import com.example.urlshort.repository.UrlMappingRepository;
import org.springframework.cache.annotation.Cacheable;
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

    public UrlService(UrlMappingRepository repository, Base62Generator generator,
                      SnowflakeIdGenerator snowflake, UrlProperties props) {
        this.repository = repository;
        this.generator = generator;
        this.snowflake = snowflake;
        this.props = props;
    }

    public UrlMapping create(String originalUrl) {
        Instant expiresAt = Instant.now().plus(props.ttlDays(), ChronoUnit.DAYS);
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String shortCode = generator.generate(Base62Generator.DEFAULT_LENGTH);
            if (!repository.existsByShortCode(shortCode)) {
                long id = snowflake.nextId();
                return repository.save(UrlMapping.builder()
                        .id(id)
                        .shortCode(shortCode)
                        .originalUrl(originalUrl)
                        .expiresAt(expiresAt)
                        .build());
            }
        }
        throw new IllegalStateException("failed to generate unique short code");
    }

    @Cacheable(value = "url", key = "#shortCode", unless = "#result.isEmpty()")
    public Optional<UrlMapping> find(String shortCode) {
        return repository.findByShortCode(shortCode);
    }
}
