package com.example.urlshort.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HotKeySet 단위 테스트")
class HotKeySetTest {

    HotKeySet hotKeys;

    @BeforeEach
    void setUp() {
        hotKeys = new HotKeySet(new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("목록을 아직 못 받았으면 전부 허용한다 (콜드 스타트에 L1이 죽지 않도록)")
    void admits_everything_before_first_snapshot() {
        assertThat(hotKeys.admits("anything")).isTrue();
        assertThat(hotKeys.version()).isEqualTo(-1);
        assertThat(hotKeys.size()).isZero();
    }

    @Test
    @DisplayName("스냅샷을 받으면 목록에 있는 키만 허용한다")
    void admits_only_listed_keys_after_snapshot() {
        hotKeys.replace(1, Set.of("hot1", "hot2"));

        assertThat(hotKeys.admits("hot1")).isTrue();
        assertThat(hotKeys.admits("hot2")).isTrue();
        assertThat(hotKeys.admits("cold")).isFalse();
        assertThat(hotKeys.size()).isEqualTo(2);
        assertThat(hotKeys.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("더 낮거나 같은 버전은 무시한다 (메시지 순서가 뒤집혀도 최신이 유지됨)")
    void ignores_older_version() {
        hotKeys.replace(5, Set.of("new"));

        boolean applied = hotKeys.replace(4, Set.of("old"));

        assertThat(applied).isFalse();
        assertThat(hotKeys.admits("new")).isTrue();
        assertThat(hotKeys.admits("old")).isFalse();
        assertThat(hotKeys.version()).isEqualTo(5);
    }

    @Test
    @DisplayName("증분은 baseVersion이 맞을 때만 적용된다")
    void applies_delta_on_matching_base() {
        hotKeys.replace(10, Set.of("a", "b"));

        boolean applied = hotKeys.applyDelta(11, 10, Set.of("c"), Set.of("a"));

        assertThat(applied).isTrue();
        assertThat(hotKeys.admits("b")).isTrue();
        assertThat(hotKeys.admits("c")).isTrue();
        assertThat(hotKeys.admits("a")).isFalse();
        assertThat(hotKeys.version()).isEqualTo(11);
        assertThat(hotKeys.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("중간 메시지를 놓치면 증분을 버리고 다음 스냅샷을 기다린다")
    void discards_delta_on_version_gap() {
        hotKeys.replace(10, Set.of("a"));

        boolean applied = hotKeys.applyDelta(12, 11, Set.of("z"), Set.of());

        assertThat(applied).isFalse();
        assertThat(hotKeys.admits("z")).isFalse();
        assertThat(hotKeys.version()).isEqualTo(10);
    }

    @Test
    @DisplayName("어긋난 뒤에도 전체 스냅샷이 오면 복구된다")
    void recovers_from_gap_with_snapshot() {
        hotKeys.replace(10, Set.of("a"));
        hotKeys.applyDelta(12, 11, Set.of("z"), Set.of());   // 유실로 버려짐

        hotKeys.replace(13, Set.of("z"));

        assertThat(hotKeys.admits("z")).isTrue();
        assertThat(hotKeys.version()).isEqualTo(13);
    }
}
