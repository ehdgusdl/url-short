package com.example.urlshort.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 주기적으로 ClickHouse 랭킹을 조회해 핫키를 발행한다.
 *
 * <p>핫키는 매 요청마다 바뀌지 않는다. 요청 경로에서 랭킹을 계산하는 대신 주기적으로 쿼리 한 방이면 충분하다.
 */
@Component
public class HotKeyScheduler {

    private static final Logger log = LoggerFactory.getLogger(HotKeyScheduler.class);

    private final ClickStatsRepository repository;
    private final HotKeyPublisher publisher;
    private final HotKeyProperties props;

    public HotKeyScheduler(ClickStatsRepository repository, HotKeyPublisher publisher, HotKeyProperties props) {
        this.repository = repository;
        this.publisher = publisher;
        this.props = props;
    }

    @Scheduled(initialDelayString = "${APP_HOTKEY_INITIAL_DELAY_MS:20000}",
            fixedDelayString = "${APP_HOTKEY_INTERVAL_MS:300000}")
    public void refresh() {
        try {
            long distinct = repository.distinctKeys(props.lookbackDays());
            if (distinct == 0) {
                // 아직 집계된 클릭이 없다. 빈 목록을 발행하면 redirect가 전부 허용 모드로 돌아가므로 건너뛴다.
                log.debug("no click data yet, skipping hot key publish");
                return;
            }
            int topN = props.topN(distinct);
            List<String> keys = repository.topStats(props.lookbackDays(), topN)
                    .stream().map(ClickStats::shortCode).toList();
            if (keys.isEmpty()) {
                return;
            }
            publisher.publish(keys);
            log.info("hot key refresh: distinct={} topN={} published={}", distinct, topN, keys.size());
        } catch (Exception e) {
            // ClickHouse가 아직 안 떴거나 집계 전이면 다음 주기에 다시 시도한다.
            log.warn("hot key refresh failed (will retry next cycle): {}", e.getMessage());
        }
    }
}
