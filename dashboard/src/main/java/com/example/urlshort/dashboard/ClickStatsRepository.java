package com.example.urlshort.dashboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ClickHouse 집계 테이블 조회.
 *
 * <p>{@code click_daily}는 AggregatingMergeTree라 저장된 값이 중간 상태(AggregateFunction)다.
 * 읽을 때 {@code uniqMerge}로 합쳐야 실제 개수가 나온다. 중복 제거 기준은 발행 시 실어 보낸 event_id다.
 */
@Repository
public class ClickStatsRepository {

    private final JdbcTemplate jdbc;

    public ClickStatsRepository(JdbcTemplate clickHouseJdbcTemplate) {
        this.jdbc = clickHouseJdbcTemplate;
    }

    /** 집계 대상 기간의 서로 다른 키 개수. 핫키 목표치(상위 p%)를 계산하는 분모다. */
    public long distinctKeys(int lookbackDays) {
        Long value = jdbc.queryForObject(
                "SELECT uniq(short_code) FROM click_daily WHERE day >= today() - ?",
                Long.class, lookbackDays);
        return value == null ? 0L : value;
    }

    /** 화면용 상위 N개 클릭수. */
    public List<ClickStats> topStats(int lookbackDays, int limit) {
        return jdbc.query(
                "SELECT short_code, uniqMerge(clicks) AS c FROM click_daily WHERE day >= today() - ? "
                        + "GROUP BY short_code ORDER BY c DESC LIMIT ?",
                (rs, rowNum) -> new ClickStats(rs.getString(1), rs.getLong(2)),
                lookbackDays, limit);
    }

    /** 단일 코드의 클릭수. */
    public long clicksOf(String shortCode, int lookbackDays) {
        Long value = jdbc.queryForObject(
                "SELECT uniqMerge(clicks) FROM click_daily WHERE short_code = ? AND day >= today() - ?",
                Long.class, shortCode, lookbackDays);
        return value == null ? 0L : value;
    }
}
