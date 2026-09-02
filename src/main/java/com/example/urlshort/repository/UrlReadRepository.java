package com.example.urlshort.repository;

import com.example.urlshort.domain.UrlRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface UrlReadRepository extends JpaRepository<UrlRead, String> {

    @Modifying
    @Transactional
    long deleteByShortCode(String shortCode);

    @Modifying
    @Transactional
    long deleteAllByExpiresAtBefore(Instant cutoff);
}
