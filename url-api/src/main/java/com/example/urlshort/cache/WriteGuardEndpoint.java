package com.example.urlshort.cache;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Write-Through 선입력(prime)을 런타임에 켜고 끄는 액추에이터 엔드포인트(생성 직후 404 검증용).
 *
 * <p>선입력은 생성 시 L2(Redis)에 값을 미리 심어, 생성 직후 조회가 Replication Lag 구간의 Replica로
 * 내려가 404를 반환하는 것을 캐시 히트로 흡수한다. 검증 시 이 엔드포인트로 선입력을 토글해 실험군을 구성한다.
 * <ul>
 *   <li>Before(음성대조군): prime=off → 생성 직후 404 재현</li>
 *   <li>After(적용군): prime=on → 404가 0으로 수렴</li>
 * </ul>
 *
 * <ul>
 *   <li>GET  /actuator/writeguard → 현재 선입력 적용 여부 조회</li>
 *   <li>POST /actuator/writeguard {"prime": false} → 선입력 on/off 설정</li>
 * </ul>
 */
@Component
@Endpoint(id = "writeguard")
public class WriteGuardEndpoint {

    private final UrlCacheWriter cache;

    public WriteGuardEndpoint(UrlCacheWriter cache) {
        this.cache = cache;
    }

    @ReadOperation
    public Map<String, Object> status() {
        return Map.of("prime", cache.isPrimeEnabled());
    }

    @WriteOperation
    public Map<String, Object> set(boolean prime) {
        cache.setPrimeEnabled(prime);
        return status();
    }
}
