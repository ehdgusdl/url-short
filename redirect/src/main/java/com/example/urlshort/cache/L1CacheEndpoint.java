package com.example.urlshort.cache;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.lang.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * L1(로컬 Caffeine) 캐시를 런타임에 켜고 끄는 액추에이터 엔드포인트.
 *
 * <p>벤치마크에서 앱 재시작 없이 "Redis 단독(L1 off)" ↔ "레이어드(L1 on)"를 전환하기 위한 용도다.
 * k6 스크립트의 setup 단계에서 이 엔드포인트를 호출해 모드를 맞춘다.
 *
 * <ul>
 *   <li>GET  /actuator/l1cache            → 현재 L1 활성화 여부 조회</li>
 *   <li>POST /actuator/l1cache {"enabled": false} → L1 on/off 설정(끌 때 로컬 캐시 즉시 비움)</li>
 * </ul>
 */
@Component
@Endpoint(id = "l1cache")
public class L1CacheEndpoint {

    private final LayeredUrlCache cache;
    private final HotKeySet hotKeys;

    public L1CacheEndpoint(LayeredUrlCache cache, HotKeySet hotKeys) {
        this.cache = cache;
        this.hotKeys = hotKeys;
    }

    @ReadOperation
    public Map<String, Object> status() {
        return Map.of(
                "l1Enabled", cache.isL1Enabled(),
                "mode", cache.isL1Enabled() ? "layered" : "redis-only",
                "entries", cache.localSize(),
                "hotKeyVersion", hotKeys.version(),
                "hotKeySize", hotKeys.size(),
                "admissionEnabled", hotKeys.isEnabled()
        );
    }

    /**
     * L1과 admission을 각각 켜고 끈다. 둘 다 선택 항목이라 보낸 것만 반영된다.
     * <ul>
     *   <li>{@code {"enabled": false}} → Redis 단독 모드</li>
     *   <li>{@code {"admission": false}} → 전량 적재(핫키 제한 해제, 벤치 대조군)</li>
     * </ul>
     */
    @WriteOperation
    public Map<String, Object> set(@Nullable Boolean enabled, @Nullable Boolean admission) {
        if (enabled != null) {
            cache.setL1Enabled(enabled);
        }
        if (admission != null) {
            hotKeys.setEnabled(admission);
        }
        return status();
    }
}
