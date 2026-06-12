package com.example.urlshort.config;

import com.example.urlshort.id.SnowflakeIdGenerator;
import com.example.urlshort.id.SnowflakeProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SnowflakeProperties.class)
public class SnowflakeConfig {

    @Bean
    SnowflakeIdGenerator snowflakeIdGenerator(SnowflakeProperties props) {
        return new SnowflakeIdGenerator(props.datacenterId(), props.workerId());
    }
}
