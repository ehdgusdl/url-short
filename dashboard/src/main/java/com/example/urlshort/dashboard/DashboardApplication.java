package com.example.urlshort.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 대시보드 서비스. ClickHouse 집계에서 상위 조회 키를 뽑아 Redis Pub/Sub으로 발행하고,
 * 화면용 클릭수 조회 API를 제공한다.
 */
@SpringBootApplication
@EnableScheduling
public class DashboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(DashboardApplication.class, args);
    }
}
