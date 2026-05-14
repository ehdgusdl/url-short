package com.example.urlshort.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "url_mapping")
public class UrlMapping {

    @Id
    private Long id;

    @Column(nullable = false, unique = true, length = 16)
    private String shortCode;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String originalUrl;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    protected UrlMapping() {
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    public static UrlMapping of(Long id, String shortCode, String originalUrl, Instant expiresAt) {
        UrlMapping mapping = new UrlMapping();
        mapping.id = id;
        mapping.shortCode = shortCode;
        mapping.originalUrl = originalUrl;
        mapping.expiresAt = expiresAt;
        return mapping;
    }

    public Long getId() {
        return id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
