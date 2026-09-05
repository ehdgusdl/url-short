package com.example.urlshort;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 리다이렉트 서비스. L1(Caffeine) + L2(Redis) 읽기 경로를 담당하고 클릭 이벤트를 Kafka로 발행한다. */
@SpringBootApplication
@EnableScheduling
public class RedirectApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedirectApplication.class, args);
    }
}
