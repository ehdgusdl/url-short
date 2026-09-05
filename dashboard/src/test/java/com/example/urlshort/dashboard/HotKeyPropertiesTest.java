package com.example.urlshort.dashboard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HotKeyProperties.topN 단위 테스트")
class HotKeyPropertiesTest {

    // 힙 예산 102MB / 엔트리 536B ≈ 190,000건이 예산 상한이다.
    private final HotKeyProperties props = new HotKeyProperties(0.10, 107_374_182L, 536L, 6);

    @Test
    @DisplayName("키가 적으면 분포(상위 10%)가 상한을 정한다")
    void distribution_caps_when_keys_are_few() {
        assertThat(props.topN(1_000_000)).isEqualTo(100_000);
    }

    @Test
    @DisplayName("키가 많아지면 힙 예산이 상한을 정한다 (캐시가 URL 총량에 정비례하지 않게)")
    void budget_caps_when_keys_grow() {
        int atThreeMillion = props.topN(3_000_000);
        int atTenMillion = props.topN(10_000_000);

        // 분포가 원하는 값(300,000)보다 작다 = 예산이 잡았다
        assertThat(atThreeMillion).isLessThan(300_000);
        // 키가 3배 더 늘어도 상한은 그대로다
        assertThat(atTenMillion).isEqualTo(atThreeMillion);
    }

    @Test
    @DisplayName("키가 아주 적어도 최소 1건은 담는다")
    void never_returns_zero() {
        assertThat(props.topN(1)).isEqualTo(1);
    }
}
