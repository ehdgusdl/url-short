package com.example.urlshort.config.routing;

/**
 * 현재 스레드가 어느 DataSource로 라우팅될지를 명시적으로 지정하는 ThreadLocal 컨텍스트.
 *
 * <p>Replication Lag 구간에서 최근 Write 키를 Primary로 강제 라우팅(Primary Fallback)하거나,
 * 유일성 검증·쓰기를 Primary로 고정할 때 사용한다. 값이 없으면 RoutingDataSource가
 * 트랜잭션 readOnly 여부로 기본 라우팅한다.
 */
public final class DataSourceContextHolder {

    private static final ThreadLocal<DataSourceType> CONTEXT = new ThreadLocal<>();

    private DataSourceContextHolder() {
    }

    public static void set(DataSourceType type) {
        CONTEXT.set(type);
    }

    public static DataSourceType get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
