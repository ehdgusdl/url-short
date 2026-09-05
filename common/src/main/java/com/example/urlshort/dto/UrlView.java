package com.example.urlshort.dto;

import com.example.urlshort.domain.UrlMapping;

import java.io.Serializable;
import java.time.Instant;

/**
 * 캐시(L1/L2)에 저장·전송되는 불변 읽기 전용 뷰.
 * JPA 엔티티(UrlMapping)를 직접 직렬화하지 않고 필요한 필드만 담아 캐시 일관성과 직렬화 안정성을 확보한다.
 */
public record UrlView(String shortCode, String originalUrl, Instant expiresAt) implements Serializable {

    public static UrlView from(UrlMapping mapping) {
        return new UrlView(mapping.getShortCode(), mapping.getOriginalUrl(), mapping.getExpiresAt());
    }
}
