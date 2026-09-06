package com.example.urlshort.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 주기적으로 ClickHouse 랭킹을 조회해 핫키를 발행한다.
 *
 * <p>핫키는 매 요청마다 바뀌지 않는다. 요청 경로에서 랭킹을 계산하는 대신 주기적으로 쿼리 한 방이면 충분하다.
 *
 * <p>발행은 한 인스턴스만 한다. {@link HotKeyPublisher}가 직전 목록과 버전을 프로세스 안에 들고
 * 증분을 만들기 때문이다. dashboard가 두 대면 각자 자기 기준으로 증분을 만들어 보내고, 받는 쪽은
 * baseVersion이 안 맞아 매번 전체 스냅샷을 다시 읽는다. 목록이 깨지진 않지만 증분의 이득이 사라진다.
 */
@Component
public class HotKeyScheduler {

    private static final Logger log = LoggerFactory.getLogger(HotKeyScheduler.class);

    /** 발행 리더를 가리키는 Redis 키. */
    private static final String LEADER_KEY = "url-cache:hotkeys:publisher";

    private final ClickStatsRepository repository;
    private final HotKeyPublisher publisher;
    private final HotKeyProperties props;
    private final StringRedisTemplate redis;

    /** 이 인스턴스의 식별자. 리더 임차를 연장할 때 내 것인지 확인하는 데 쓴다. */
    private final String instanceId = UUID.randomUUID().toString();
    private final Duration leaseTtl;

    public HotKeyScheduler(ClickStatsRepository repository, HotKeyPublisher publisher,
                           HotKeyProperties props, StringRedisTemplate redis,
                           @Value("${APP_HOTKEY_INTERVAL_MS:300000}") long intervalMs) {
        this.repository = repository;
        this.publisher = publisher;
        this.props = props;
        this.redis = redis;
        // 한 주기를 놓쳐도 리더가 유지되도록 주기의 2.5배. 죽으면 그 시간 안에 다른 인스턴스가 가져간다.
        this.leaseTtl = Duration.ofMillis(Math.max(1000L, intervalMs * 5 / 2));
    }

    /**
     * 발행 리더인지. 비어 있으면 가져오고, 내 것이면 임차를 연장한다.
     * Redis가 죽으면 아무도 리더가 아니게 되지만, 그때는 발행해봐야 전달되지도 않는다.
     */
    private boolean isPublisher() {
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(LEADER_KEY, instanceId, leaseTtl);
            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }
            if (instanceId.equals(redis.opsForValue().get(LEADER_KEY))) {
                redis.expire(LEADER_KEY, leaseTtl);
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("hot key leader check failed, skipping this cycle: {}", e.getMessage());
            return false;
        }
    }

    @Scheduled(initialDelayString = "${APP_HOTKEY_INITIAL_DELAY_MS:20000}",
            fixedDelayString = "${APP_HOTKEY_INTERVAL_MS:300000}")
    public void refresh() {
        if (!isPublisher()) {
            log.debug("not the hot key publisher, skipping this cycle");
            return;
        }
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
