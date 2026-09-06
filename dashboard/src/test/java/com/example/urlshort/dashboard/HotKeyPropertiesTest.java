package com.example.urlshort.dashboard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HotKeyProperties.topN 단위 테스트")
class HotKeyPropertiesTest {

    // 엔트리 1건 576B(jol 실측). 힙 예산 107,374,182B / 576B = 186,413건이 예산 상한이다.
    private static final long BYTES_PER_ENTRY = 576L;
    private static final long HEAP_BUDGET_BYTES = 107_374_182L;

    private final HotKeyProperties props =
            new HotKeyProperties(0.10, HEAP_BUDGET_BYTES, BYTES_PER_ENTRY, 6);

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
    @DisplayName("예산이 잡은 상한은 실제 점유로 환산해도 예산을 넘지 않는다")
    void budget_cap_stays_within_the_budget() {
        // 엔트리 크기를 실제보다 작게 잡으면 여기서 예산을 넘긴다.
        long occupied = (long) props.topN(100_000_000) * BYTES_PER_ENTRY;

        assertThat(occupied).isLessThanOrEqualTo(HEAP_BUDGET_BYTES);
    }

    @Test
    @DisplayName("기본 설정의 엔트리 크기는 실측값이다")
    void shipped_default_matches_the_measured_entry_size() throws IOException {
        // redirect의 CacheMetricsReporter.BYTES_PER_ENTRY와 어긋나면 예산 계산이 틀어진다.
        assertThat(shippedDefaults().bytesPerEntry()).isEqualTo(BYTES_PER_ENTRY);
        assertThat(shippedDefaults().heapBudgetBytes()).isEqualTo(HEAP_BUDGET_BYTES);
    }

    /** 실제로 배포되는 application.yml을 그대로 바인딩한다. */
    private HotKeyProperties shippedDefaults() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(env.getPropertySources()::addFirst);
        return Binder.get(env).bind("app.hotkey", HotKeyProperties.class).get();
    }
}
