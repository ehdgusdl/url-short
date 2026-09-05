package com.example.urlshort.dashboard;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/** ClickHouse 접속 구성. 이 서비스의 유일한 DataSource다. */
@Configuration
@EnableConfigurationProperties(HotKeyProperties.class)
public class ClickHouseConfig {

    @Bean
    public DataSource clickHouseDataSource(
            @org.springframework.beans.factory.annotation.Value("${app.clickhouse.url}") String url,
            @org.springframework.beans.factory.annotation.Value("${app.clickhouse.username:default}") String username,
            @org.springframework.beans.factory.annotation.Value("${app.clickhouse.password:}") String password) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .driverClassName("com.clickhouse.jdbc.ClickHouseDriver")
                .build();
        ds.setPoolName("clickhouse-pool");
        ds.setMaximumPoolSize(4);
        return ds;
    }

    @Bean
    public JdbcTemplate clickHouseJdbcTemplate(DataSource clickHouseDataSource) {
        return new JdbcTemplate(clickHouseDataSource);
    }
}
