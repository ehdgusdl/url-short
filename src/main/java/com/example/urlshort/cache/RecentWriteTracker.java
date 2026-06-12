package com.example.urlshort.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 최근 Write된 shortCode를 짧은 TTL 동안 Redis에 표시해 Primary Fallback 라우팅의 근거를 제공한다.
 *
 * <p>Replication Lag(p99 약 300ms) 구간 동안 최근 생성된 키는 Replica가 아직 갖고 있지 않을 수 있으므로,
 * TTL 내에는 Primary로 라우팅해 생성 직후 404 Stale 응답을 차단한다. TTL은 모든 인스턴스가 공유하도록
 * Redis에 저장한다.
 */
@Component
public class RecentWriteTracker {

    private static final String KEY_PREFIX = "recent-write:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RecentWriteTracker(StringRedisTemplate redis,
                              @Value("${app.cache.recent-write-ttl:2s}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    public void mark(String shortCode) {
        redis.opsForValue().set(KEY_PREFIX + shortCode, "1", ttl);
    }

    public boolean isRecent(String shortCode) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + shortCode));
    }
}
