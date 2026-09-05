package com.example.urlshort.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.url")
public record UrlProperties(int ttlDays, String cleanupCron) {
}
