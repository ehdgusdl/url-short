package com.example.urlshort.config.routing;

/**
 * CQRS 읽기/쓰기 자원 격리를 위한 DataSource 라우팅 키.
 */
public enum DataSourceType {
    PRIMARY,
    REPLICA
}
