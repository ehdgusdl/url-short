package com.example.urlshort;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class UrlShortApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortApplication.class, args);
    }
}
