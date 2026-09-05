package com.example.urlshort;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** URL CRUD 서비스. 쓰기는 Primary, 읽기는 Replica로 라우팅하며 L2 선입력/무효화를 발행한다. */
@SpringBootApplication
public class UrlApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlApiApplication.class, args);
    }
}
