package com.example.urlshort.repository;

import com.example.urlshort.domain.UrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    Optional<UrlMapping> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    /** 목록 화면용 최근 생성분. 페이지네이션이 필요해지기 전까지는 상위 50건으로 충분하다. */
    List<UrlMapping> findTop50ByOrderByCreatedAtDesc();

    @Modifying
    @Transactional
    long deleteByShortCode(String shortCode);

    @Modifying
    @Transactional
    long deleteAllByExpiresAtBefore(Instant cutoff);
}
