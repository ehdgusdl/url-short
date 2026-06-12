package com.example.urlshort.config;

import com.example.urlshort.config.routing.DataSourceType;
import com.example.urlshort.config.routing.RoutingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * CQRS Primary/Replica DataSource 분리 구성.
 *
 * <p>{@link RoutingDataSource}가 컨텍스트/트랜잭션 readOnly 여부에 따라 Primary 또는 Replica를 선택하고,
 * {@link LazyConnectionDataSourceProxy}로 감싸 실제 커넥션 획득 시점(트랜잭션 readOnly 플래그가 확정된 뒤)에
 * 라우팅 키가 결정되도록 한다. 로컬/테스트에서는 replica URL을 primary와 동일하게 두면 코드는 그대로 둔 채
 * 단일 DB로 동작하고, 운영에서는 실제 복제 노드를 가리키도록 환경변수만 바꾸면 된다.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource primaryDataSource(
            @Value("${app.datasource.primary.url}") String url,
            @Value("${app.datasource.primary.username}") String username,
            @Value("${app.datasource.primary.password}") String password) {
        return build(url, username, password, "primary-pool");
    }

    @Bean
    public DataSource replicaDataSource(
            @Value("${app.datasource.replica.url}") String url,
            @Value("${app.datasource.replica.username}") String username,
            @Value("${app.datasource.replica.password}") String password) {
        return build(url, username, password, "replica-pool");
    }

    @Bean
    public DataSource routingDataSource(
            @Qualifier("primaryDataSource") DataSource primary,
            @Qualifier("replicaDataSource") DataSource replica) {
        RoutingDataSource routing = new RoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(DataSourceType.PRIMARY, primary);
        targets.put(DataSourceType.REPLICA, replica);
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(primary);
        routing.afterPropertiesSet();
        return routing;
    }

    @Primary
    @Bean
    public DataSource dataSource(@Qualifier("routingDataSource") DataSource routingDataSource) {
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }

    private DataSource build(String url, String username, String password, String poolName) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
        ds.setPoolName(poolName);
        return ds;
    }
}
