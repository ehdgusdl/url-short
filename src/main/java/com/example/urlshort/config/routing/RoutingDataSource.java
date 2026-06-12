package com.example.urlshort.config.routing;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 실행 시점의 컨텍스트에 따라 Primary/Replica DataSource를 선택하는 라우팅 DataSource.
 *
 * <p>우선순위:
 * <ol>
 *   <li>{@link DataSourceContextHolder}에 명시된 값(Primary Fallback / 쓰기 고정)</li>
 *   <li>그 외에는 트랜잭션 readOnly 여부 — readOnly면 Replica, 아니면 Primary</li>
 * </ol>
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        DataSourceType explicit = DataSourceContextHolder.get();
        if (explicit != null) {
            return explicit;
        }
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? DataSourceType.REPLICA
                : DataSourceType.PRIMARY;
    }
}
