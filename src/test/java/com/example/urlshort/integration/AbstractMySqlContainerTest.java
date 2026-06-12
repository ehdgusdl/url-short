package com.example.urlshort.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public abstract class AbstractMySqlContainerTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("urlshort")
            .withUsername("urlshort")
            .withPassword("urlshort");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        // 통합 테스트에서는 단일 MySQL 컨테이너를 Primary/Replica 양쪽으로 사용한다(라우팅 코드는 동일).
        registry.add("app.datasource.primary.url", MYSQL::getJdbcUrl);
        registry.add("app.datasource.primary.username", MYSQL::getUsername);
        registry.add("app.datasource.primary.password", MYSQL::getPassword);
        registry.add("app.datasource.replica.url", MYSQL::getJdbcUrl);
        registry.add("app.datasource.replica.username", MYSQL::getUsername);
        registry.add("app.datasource.replica.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }
}
