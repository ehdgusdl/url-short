package com.example.urlshort.service;

import com.example.urlshort.cache.LayeredUrlCache;
import com.example.urlshort.config.routing.DataSourceContextHolder;
import com.example.urlshort.config.routing.DataSourceType;
import com.example.urlshort.dto.UrlView;
import com.example.urlshort.repository.UrlMappingRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 리다이렉트 읽기 경로. 캐시 미스만 Replica DB로 내려간다. */
@Service
public class RedirectService {

    private final UrlMappingRepository repository;
    private final LayeredUrlCache cache;

    // 캐시 미스 시 Replica로 내려간 실제 DB 조회 수.
    private final Counter replicaReads;
    // Replica 에도 없어 Primary 로 한 번 더 내려간 수(= 복제 지연 구간 또는 없는 코드).
    private final Counter primaryReads;
    // DB 동시 조회 상한(Bulkhead)에 막혀 거절한 수.
    private final Counter dbRejected;

    /**
     * DB 로더 동시 실행 상한. Redis 차단기가 열리면 L1 미스가 전부 여기로 몰린다.
     * Hikari는 풀이 차면 connectionTimeout(30초)까지 스레드를 붙잡으므로, 그 전에 짧게 기다리고 거절한다.
     */
    private final Semaphore dbPermits;
    private final Duration dbWait;

    public RedirectService(UrlMappingRepository repository, LayeredUrlCache cache,
                           @Value("${app.db.bulkhead.max-concurrent:10}") int maxConcurrent,
                           @Value("${app.db.bulkhead.wait:50ms}") Duration dbWait,
                           MeterRegistry registry) {
        this.repository = repository;
        this.cache = cache;
        this.dbPermits = new Semaphore(maxConcurrent);
        this.dbWait = dbWait;
        this.dbRejected = Counter.builder("urlcache.db.rejected").register(registry);
        this.replicaReads = Counter.builder("urlcache.db.reads").tag("datasource", "replica").register(registry);
        this.primaryReads = Counter.builder("urlcache.db.reads").tag("datasource", "primary").register(registry);
    }

    public Optional<UrlView> find(String shortCode) {
        return cache.get(shortCode, () -> loadFromDb(shortCode));
    }

    private Optional<UrlView> loadFromDb(String shortCode) {
        if (!acquireDbPermit()) {
            dbRejected.increment();
            throw new DbBusyException();
        }
        try {
            return loadFromReplicaThenPrimary(shortCode);
        } finally {
            dbPermits.release();
        }
    }

    private boolean acquireDbPermit() {
        try {
            return dbPermits.tryAcquire(dbWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Optional<UrlView> loadFromReplicaThenPrimary(String shortCode) {
        Optional<UrlView> fromReplica = read(DataSourceType.REPLICA, replicaReads, shortCode);
        if (fromReplica.isPresent()) {
            return fromReplica;
        }
        // Replica 에 없다고 없는 게 아니다. 방금 생성된 키는 복제가 아직 안 왔을 수 있다.
        // 선입력(prime)이 유일한 방어였는데 그건 Redis 가 살아 있을 때만 동작한다.
        // 미스일 때만 한 번 더 보므로 히트 경로의 Primary 부하는 늘지 않는다.
        return read(DataSourceType.PRIMARY, primaryReads, shortCode);
    }

    private Optional<UrlView> read(DataSourceType source, Counter counter, String shortCode) {
        DataSourceContextHolder.set(source);
        counter.increment();
        try {
            return repository.findByShortCode(shortCode).map(UrlView::from);
        } finally {
            DataSourceContextHolder.clear();
        }
    }
}
