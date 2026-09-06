package com.example.urlshort.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dashboard가 보내는 세 가지 페이로드를 redirect가 제대로 해석하는지.
 *
 * <p>발행 측과 수신 측이 서로 다른 모듈이라 컴파일러가 형식을 맞춰주지 않는다.
 * 여기 JSON은 HotKeyPublisher가 실제로 만드는 형태를 그대로 옮긴 것이다.
 */
@DisplayName("HotKeyListener 단위 테스트")
class HotKeyListenerTest {

    private static final String SNAPSHOT_KEY = "url-cache:hotkeys:snapshot";

    private HotKeySet hotKeys;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private HotKeyListener listener;
    private SimpleMeterRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = new SimpleMeterRegistry();
        hotKeys = new HotKeySet(Duration.ofMinutes(2), registry);
        redis = Mockito.mock(StringRedisTemplate.class);
        ops = Mockito.mock(ValueOperations.class);
        Mockito.when(redis.opsForValue()).thenReturn(ops);
        listener = new HotKeyListener(hotKeys, redis, registry);
    }

    private void receive(String json) {
        Message message = new DefaultMessage(new byte[0], json.getBytes(StandardCharsets.UTF_8));
        listener.onMessage(message, null);
    }

    private double messages(String result) {
        return registry.get("urlcache.hotkeys.messages").tag("result", result).counter().count();
    }

    @Test
    @DisplayName("SNAPSHOT: 목록을 통째로 교체한다")
    void applies_a_snapshot() {
        receive("{\"type\":\"SNAPSHOT\",\"version\":100,\"keys\":[\"a\",\"b\"]}");

        assertThat(hotKeys.version()).isEqualTo(100);
        assertThat(hotKeys.admits("a")).isTrue();
        assertThat(hotKeys.admits("c")).isFalse();
        assertThat(messages("applied")).isEqualTo(1);
    }

    @Test
    @DisplayName("DELTA: baseVersion이 맞으면 바뀐 것만 적용한다")
    void applies_a_delta_on_the_matching_base() {
        receive("{\"type\":\"SNAPSHOT\",\"version\":100,\"keys\":[\"a\",\"b\"]}");

        receive("{\"type\":\"DELTA\",\"version\":101,\"baseVersion\":100,"
                + "\"add\":[\"c\"],\"remove\":[\"a\"]}");

        assertThat(hotKeys.version()).isEqualTo(101);
        assertThat(hotKeys.admits("c")).isTrue();
        assertThat(hotKeys.admits("a")).isFalse();
        assertThat(hotKeys.admits("b")).isTrue();
    }

    @Test
    @DisplayName("DELTA: 기준이 어긋나면 다음 주기를 기다리지 않고 복구 지점에서 다시 맞춘다")
    void resyncs_from_the_recovery_point_when_the_base_does_not_match() {
        receive("{\"type\":\"SNAPSHOT\",\"version\":100,\"keys\":[\"a\"]}");
        // 중간 메시지를 놓쳐 base가 어긋난 상황
        Mockito.when(ops.get(SNAPSHOT_KEY))
                .thenReturn("{\"type\":\"SNAPSHOT\",\"version\":150,\"keys\":[\"x\",\"y\"]}");

        receive("{\"type\":\"DELTA\",\"version\":120,\"baseVersion\":110,"
                + "\"add\":[\"z\"],\"remove\":[]}");

        assertThat(hotKeys.version()).isEqualTo(150);
        assertThat(hotKeys.admits("x")).isTrue();
        assertThat(hotKeys.admits("z")).isFalse();   // 어긋난 증분은 적용하지 않는다
    }

    @Test
    @DisplayName("SNAPSHOT_REF: 본문 없이 신호만 오면 복구 지점을 직접 읽는다")
    void reads_the_recovery_point_on_a_reference() {
        // 목록이 크면 채널로 보내지 않는다. 그대로 보내면 적용이 밀려 버전이 역전된다.
        Mockito.when(ops.get(SNAPSHOT_KEY))
                .thenReturn("{\"type\":\"SNAPSHOT\",\"version\":200,\"keys\":[\"p\",\"q\"]}");

        receive("{\"type\":\"SNAPSHOT_REF\",\"version\":200}");

        assertThat(hotKeys.version()).isEqualTo(200);
        assertThat(hotKeys.admits("p")).isTrue();
        assertThat(messages("applied")).isEqualTo(1);
    }

    @Test
    @DisplayName("오래된 버전은 무시한다")
    void ignores_an_older_version() {
        receive("{\"type\":\"SNAPSHOT\",\"version\":100,\"keys\":[\"a\"]}");

        receive("{\"type\":\"SNAPSHOT\",\"version\":50,\"keys\":[\"old\"]}");

        assertThat(hotKeys.version()).isEqualTo(100);
        assertThat(hotKeys.admits("old")).isFalse();
        assertThat(messages("skipped")).isEqualTo(1);
    }

    @Test
    @DisplayName("깨진 메시지는 이번 주기만 건너뛴다 (구독이 죽으면 안 된다)")
    void survives_a_malformed_message() {
        receive("{\"type\":\"SNAPSHOT\",\"version\":100,\"keys\":[\"a\"]}");

        receive("not json at all");

        assertThat(hotKeys.version()).isEqualTo(100);
        assertThat(hotKeys.admits("a")).isTrue();
    }

    @Test
    @DisplayName("기동 시 복구 지점에서 최신 목록을 한 번 맞춘다")
    void syncs_on_startup() {
        // 증분만 받으면 방금 뜬 인스턴스는 기준을 맞출 수 없다.
        Mockito.when(ops.get(SNAPSHOT_KEY))
                .thenReturn("{\"type\":\"SNAPSHOT\",\"version\":300,\"keys\":[\"k\"]}");

        listener.syncOnStartup();

        assertThat(hotKeys.version()).isEqualTo(300);
        assertThat(hotKeys.admits("k")).isTrue();
    }

    @Test
    @DisplayName("복구 지점이 비어 있어도 기동은 계속된다")
    void startup_sync_tolerates_an_empty_recovery_point() {
        Mockito.when(ops.get(SNAPSHOT_KEY)).thenReturn(null);

        listener.syncOnStartup();

        assertThat(hotKeys.version()).isEqualTo(-1);
    }
}
