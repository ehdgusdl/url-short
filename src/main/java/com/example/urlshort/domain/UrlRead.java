package com.example.urlshort.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * CQRS 읽기 모델. 리다이렉트 조회에 필요한 최소 컬럼만 가진다.
 *
 * <p>쓰기 모델({@link UrlMapping})과 스키마를 분리해, 리다이렉트 조회 경로가 쓰기 스키마 변경
 * (컬럼 추가, 통계/소유자 필드 등)에 영향받지 않게 한다. Primary에 기록된 이 테이블을
 * MySQL Replication이 Replica로 전파하고, 조회는 Replica의 이 테이블만 참조한다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "url_read")
public class UrlRead implements Persistable<String> {

    @Id
    @Column(length = 16)
    private String shortCode;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String originalUrl;

    @Column(nullable = false)
    private Instant expiresAt;

    public static UrlRead from(UrlMapping mapping) {
        return UrlRead.builder()
                .shortCode(mapping.getShortCode())
                .originalUrl(mapping.getOriginalUrl())
                .expiresAt(mapping.getExpiresAt())
                .build();
    }

    @Override
    public String getId() {
        return shortCode;
    }

    /** 읽기 모델은 생성 시 1회 INSERT만 하고 갱신하지 않는다 → merge 전 SELECT를 생략한다. */
    @Override
    public boolean isNew() {
        return true;
    }
}
