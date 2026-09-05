package com.example.urlshort.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * dashboard가 {@link CacheChannels#HOTKEY_CHANNEL}로 발행한 핫키 스냅샷을 받아 {@link HotKeySet}에 반영한다.
 *
 * <p>두 가지 페이로드를 받는다.
 * <ul>
 *   <li>{@code {"type":"SNAPSHOT","version":V,"keys":[...]}} — 통째 교체</li>
 *   <li>{@code {"type":"DELTA","version":V,"baseVersion":V0,"add":[...],"remove":[...]}} — 바뀐 것만</li>
 *   <li>{@code {"type":"SNAPSHOT_REF","version":V}} — 목록이 커서 복구 지점을 읽으라는 신호</li>
 * </ul>
 * 증분은 {@code baseVersion}이 맞을 때만 적용한다. 어긋나면(=메시지를 놓쳤다면) 버리고
 * 다음 주기의 전체 스냅샷으로 스스로 복구한다.
 */
@Component
public class HotKeyListener implements MessageListener {

    /** dashboard가 남기는 복구 지점. 이름이 어긋나면 기동 동기화가 조용히 실패한다. */
    private static final String SNAPSHOT_KEY = "url-cache:hotkeys:snapshot";

    private static final Logger log = LoggerFactory.getLogger(HotKeyListener.class);

    private final HotKeySet hotKeys;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Counter applied;
    private final Counter skipped;

    private final StringRedisTemplate redis;

    public HotKeyListener(HotKeySet hotKeys, StringRedisTemplate redis, MeterRegistry registry) {
        this.hotKeys = hotKeys;
        this.redis = redis;
        this.applied = Counter.builder("urlcache.hotkeys.messages").tag("result", "applied").register(registry);
        this.skipped = Counter.builder("urlcache.hotkeys.messages").tag("result", "skipped").register(registry);
    }

    /** 기동 직후 최신 목록을 한 번 맞춘다. 증분만 받으면 방금 뜬 인스턴스는 기준을 맞출 수 없다. */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (syncFromSnapshot()) {
            log.info("hot keys synced on startup: version={} size={}", hotKeys.version(), hotKeys.size());
        }
    }

    /** Redis에 보관된 최신 전체 목록을 읽어 적용한다. */
    private boolean syncFromSnapshot() {
        try {
            String body = redis.opsForValue().get(SNAPSHOT_KEY);
            if (body == null) {
                return false;
            }
            JsonNode root = mapper.readTree(body);
            return hotKeys.replace(root.path("version").asLong(-1), toSet(root.path("keys")));
        } catch (Exception e) {
            log.warn("hot key snapshot sync failed (ignored)", e);
            return false;
        }
    }

    private static Set<String> toSet(JsonNode array) {
        Set<String> out = new HashSet<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            JsonNode root = mapper.readTree(body);
            long version = root.path("version").asLong(-1);
            String type = root.path("type").asText();
            boolean ok;
            if ("SNAPSHOT_REF".equals(type)) {
                // 목록이 커서 채널로 보내지 않은 경우다. 복구 지점에서 직접 읽어온다.
                ok = syncFromSnapshot();
                log.info("hot key snapshot-ref {}: version={} size={}",
                        ok ? "applied" : "skipped", version, hotKeys.size());
            } else if ("DELTA".equals(type)) {
                long baseVersion = root.path("baseVersion").asLong(-1);
                Set<String> add = toSet(root.path("add"));
                Set<String> remove = toSet(root.path("remove"));
                ok = hotKeys.applyDelta(version, baseVersion, add, remove);
                if (!ok) {
                    // 기준이 어긋났다. 다음 주기를 기다리지 말고 복구 지점에서 바로 맞춘다.
                    ok = syncFromSnapshot();
                }
                log.info("hot key delta {}: version={} base={} +{} -{} size={}",
                        ok ? "applied" : "skipped (could not resync)",
                        version, baseVersion, add.size(), remove.size(), hotKeys.size());
            } else {
                Set<String> keys = toSet(root.path("keys"));
                ok = hotKeys.replace(version, keys);
                log.info("hot key snapshot {}: version={} size={}", ok ? "applied" : "skipped", version, keys.size());
            }
            (ok ? applied : skipped).increment();
        } catch (Exception e) {
            // 파싱 실패는 이번 주기만 건너뛴다. 다음 발행에서 복구된다.
            log.warn("failed to apply hot key snapshot (ignored)", e);
        }
    }
}
