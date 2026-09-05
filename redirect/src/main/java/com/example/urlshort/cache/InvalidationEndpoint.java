package com.example.urlshort.cache;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Pub/Sub 무효화 전파를 런타임에 켜고 끄는 액추에이터 엔드포인트(무효화 검증용).
 *
 * <p>끄면 이 인스턴스는 구독 메시지를 받아도 L1을 비우지 않아 "Pub/Sub가 없거나 메시지를 놓친 인스턴스"가 된다.
 * k6 검증 스크립트가 인스턴스 B에 이걸 걸어 <b>전파 ON(즉시 404 수렴) vs OFF(TTL까지 Stale)</b>를 대조한다.
 *
 * <ul>
 *   <li>GET  /actuator/invalidation                 → 현재 전파 적용 여부 조회</li>
 *   <li>POST /actuator/invalidation {"enabled": false} → 전파 on/off 설정</li>
 * </ul>
 */
@Component
@Endpoint(id = "invalidation")
public class InvalidationEndpoint {

    private final LayeredUrlCache cache;

    public InvalidationEndpoint(LayeredUrlCache cache) {
        this.cache = cache;
    }

    @ReadOperation
    public Map<String, Object> status() {
        return Map.of(
                "propagationEnabled", cache.isPropagationEnabled(),
                "mode", cache.isPropagationEnabled() ? "pubsub-on" : "pubsub-off"
        );
    }

    @WriteOperation
    public Map<String, Object> setEnabled(boolean enabled) {
        cache.setPropagationEnabled(enabled);
        return status();
    }
}
