package com.example.urlshort.id;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.snowflake")
public record SnowflakeProperties(long datacenterId, long workerId) {
}
