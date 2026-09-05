package com.example.urlshort.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 핫키 목록을 Redis Pub/Sub으로 전 redirect 인스턴스에 발행한다.
 *
 * <p>L1은 인스턴스마다 따로 있으므로, 계산한 목록을 모든 인스턴스에 같은 시점에 반영해야
 * 인스턴스별로 캐시 내용이 갈리지 않는다.
 *
 * <p>목록의 대부분은 주기마다 그대로다. 통째 교체하면 그 순간 L1이 비어 히트율이 바닥을 치므로
 * 바뀐 것(add/remove)만 보낸다. 다만 증분만 계속 보내면 메시지를 놓친 인스턴스가 영원히 어긋나므로,
 * 첫 발행과 {@code snapshotEvery} 주기마다는 전체 스냅샷을 보내 스스로 복구되게 한다.
 */
@Component
public class HotKeyPublisher {

    private static final Logger log = LoggerFactory.getLogger(HotKeyPublisher.class);

    /** redirect의 CacheChannels.HOTKEY_CHANNEL과 같아야 하는 와이어 계약. 토픽 이름처럼 서비스마다 따로 둔다. */
    static final String HOTKEY_CHANNEL = "url-cache:hotkeys";

    /**
     * 최신 전체 목록을 담아두는 키. 증분만 받아서는 방금 뜬 인스턴스가 기준(baseVersion)을 맞출 수 없다.
     * 채널로 요청/응답을 주고받는 대신 여기서 읽어가게 한다.
     */
    static final String SNAPSHOT_KEY = "url-cache:hotkeys:snapshot";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong version = new AtomicLong();
    private final AtomicInteger lastSize = new AtomicInteger();
    private final int snapshotEvery;
    /** 이 크기를 넘는 페이로드는 채널로 직접 보내지 않고 복구 지점 참조로 대체한다. */
    private final int refPayloadThreshold;

    // 직전에 발행한 목록. 증분(add/remove) 계산의 기준이다.
    private Set<String> lastKeys = Set.of();
    private int publishCount;

    public HotKeyPublisher(StringRedisTemplate redis,
                           @org.springframework.beans.factory.annotation.Value("${app.hotkey.snapshot-every:10}") int snapshotEvery,
                           @org.springframework.beans.factory.annotation.Value("${app.hotkey.ref-payload-threshold:262144}") int refPayloadThreshold,
                           MeterRegistry registry) {
        this.redis = redis;
        this.snapshotEvery = snapshotEvery;
        this.refPayloadThreshold = refPayloadThreshold;
        registry.gauge("hotkey.published.size", lastSize);
        registry.gauge("hotkey.published.version", version);
    }

    public synchronized void publish(List<String> keys) {
        Set<String> next = Set.copyOf(keys);
        long base = version.get();
        // 벽시계를 버전으로 쓴다. 인메모리 카운터는 재시작하면 1부터 다시 시작해
        // redirect가 "낮은 버전"으로 보고 이후 모든 스냅샷을 영구히 무시하게 된다.
        long newVersion = version.updateAndGet(prev -> Math.max(prev + 1, System.currentTimeMillis()));

        boolean fullSnapshot = lastKeys.isEmpty() || publishCount % snapshotEvery == 0;
        try {
            Map<String, Object> message;
            if (fullSnapshot) {
                message = Map.of("type", "SNAPSHOT", "version", newVersion, "keys", keys);
            } else {
                List<String> add = next.stream().filter(k -> !lastKeys.contains(k)).toList();
                List<String> remove = lastKeys.stream().filter(k -> !next.contains(k)).toList();
                if (add.isEmpty() && remove.isEmpty()) {
                    // 바뀐 게 없으면 보낼 이유도 없다. 버전만 되돌려 base를 유지한다.
                    version.set(base);
                    publishCount++;
                    return;
                }
                message = Map.of("type", "DELTA", "version", newVersion, "baseVersion", base,
                        "add", add, "remove", remove);
            }
            String payload = mapper.writeValueAsString(message);
            // 채널 발행 전에 복구 지점을 먼저 갱신한다. 순서가 뒤집히면 방금 읽어간 인스턴스가 옛 목록을 잡는다.
            redis.opsForValue().set(SNAPSHOT_KEY,
                    mapper.writeValueAsString(Map.of("type", "SNAPSHOT", "version", newVersion, "keys", keys)));
            // 큰 목록을 채널로 그대로 보내면 구독자에게 도달·적용되기까지 수십 초에서 분 단위로 밀린다.
            // 그 사이 인스턴스는 옛 목록으로 판정하므로, 임계를 넘으면 "복구 지점을 읽어라"는 신호만 보낸다.
            String sentType = String.valueOf(message.get("type"));
            int fullBytes = payload.length();
            if (fullBytes > refPayloadThreshold) {
                payload = mapper.writeValueAsString(Map.of("type", "SNAPSHOT_REF", "version", newVersion));
                sentType = "SNAPSHOT_REF";
            }
            redis.convertAndSend(HOTKEY_CHANNEL, payload);
            lastKeys = next;
            lastSize.set(next.size());
            publishCount++;
            log.info("hot keys published: type={} version={} size={} bytes={} full_bytes={}",
                    sentType, newVersion, next.size(), payload.length(), fullBytes);
        } catch (Exception e) {
            // 발행에 실패하면 버전을 되돌려 다음 주기에 같은 base로 다시 시도한다.
            version.set(base);
            log.warn("hot key publish failed (will retry next cycle)", e);
        }
    }

    public long version() {
        return version.get();
    }

    public int lastSize() {
        return lastSize.get();
    }
}
