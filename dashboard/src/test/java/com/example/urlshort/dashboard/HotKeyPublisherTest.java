package com.example.urlshort.dashboard;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HotKeyPublisher 발행 테스트")
class HotKeyPublisherTest {

    private final StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);

    private HotKeyPublisher publisher(int snapshotEvery) {
        return publisher(snapshotEvery, Integer.MAX_VALUE);
    }

    /** refThreshold를 넘는 스냅샷은 목록 대신 참조만 발행한다. */
    private HotKeyPublisher publisher(int snapshotEvery, int refThreshold) {
        Mockito.when(redis.opsForValue()).thenReturn(ops);
        return new HotKeyPublisher(redis, snapshotEvery, refThreshold, new SimpleMeterRegistry());
    }

    private List<String> published() {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        Mockito.verify(redis, Mockito.atLeastOnce())
                .convertAndSend(Mockito.eq(HotKeyPublisher.HOTKEY_CHANNEL), payload.capture());
        return payload.getAllValues();
    }

    @Test
    @DisplayName("첫 발행은 전체 스냅샷이다")
    void first_publish_is_snapshot() {
        publisher(10).publish(List.of("a", "b"));

        assertThat(published().getLast()).contains("\"type\":\"SNAPSHOT\"").contains("\"keys\"");
    }

    @Test
    @DisplayName("두 번째부터는 바뀐 것만 보낸다")
    void subsequent_publishes_send_only_the_diff() {
        HotKeyPublisher p = publisher(10);
        p.publish(List.of("a", "b"));
        p.publish(List.of("b", "c"));

        String delta = published().getLast();
        assertThat(delta).contains("\"type\":\"DELTA\"").contains("\"add\":[\"c\"]").contains("\"remove\":[\"a\"]");
    }

    @Test
    @DisplayName("바뀐 게 없으면 아예 보내지 않는다")
    void skips_publish_when_nothing_changed() {
        HotKeyPublisher p = publisher(10);
        p.publish(List.of("a"));
        long afterFirst = p.version();
        p.publish(List.of("a"));

        assertThat(published()).hasSize(1);
        assertThat(p.version()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("주기적으로 전체 스냅샷을 보내 놓친 인스턴스가 복구되게 한다")
    void resends_snapshot_periodically() {
        HotKeyPublisher p = publisher(2);   // 2번에 한 번은 스냅샷
        p.publish(List.of("a"));            // 1회차: 스냅샷(첫 발행)
        p.publish(List.of("b"));            // 2회차: 델타
        p.publish(List.of("c"));            // 3회차: 스냅샷 주기

        List<String> all = published();
        assertThat(all.get(0)).contains("SNAPSHOT");
        assertThat(all.get(1)).contains("DELTA");
        assertThat(all.get(2)).contains("SNAPSHOT");
    }

    @Test
    @DisplayName("재시작해도 버전이 이전보다 크다")
    void version_survives_restart() {
        HotKeyPublisher before = publisher(10);
        before.publish(List.of("a"));
        long last = before.version();

        HotKeyPublisher afterRestart = publisher(10);
        afterRestart.publish(List.of("a"));

        assertThat(afterRestart.version()).isGreaterThanOrEqualTo(last);
        assertThat(afterRestart.version()).isGreaterThan(1_700_000_000_000L);
    }

    @Test
    @DisplayName("증분을 보낼 때도 복구용 전체 목록을 Redis 키에 남긴다")
    void always_writes_recovery_snapshot() {
        HotKeyPublisher p = publisher(10);
        p.publish(List.of("a"));
        p.publish(List.of("a", "b"));

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        Mockito.verify(ops, Mockito.times(2))
                .set(Mockito.eq(HotKeyPublisher.SNAPSHOT_KEY), saved.capture());
        // 두 번째는 DELTA로 발행됐지만 복구 지점에는 전체 목록이 들어간다.
        assertThat(saved.getAllValues().getLast()).contains("\"type\":\"SNAPSHOT\"").contains("a").contains("b");
        assertThat(published().getLast()).contains("\"type\":\"DELTA\"");
    }

    @Test
    @DisplayName("스냅샷이 임계보다 크면 목록 대신 복구 지점 참조만 보낸다")
    void large_snapshot_is_published_as_reference() {
        publisher(10, 16).publish(List.of("a", "b", "c"));

        String sent = published().getLast();
        assertThat(sent).contains("\"type\":\"SNAPSHOT_REF\"").doesNotContain("\"keys\"");
        // 참조만 보내더라도 복구 지점에는 전체 목록이 들어가 있어야 한다.
        Mockito.verify(ops).set(Mockito.eq(HotKeyPublisher.SNAPSHOT_KEY), Mockito.contains("\"keys\""));
    }
}
