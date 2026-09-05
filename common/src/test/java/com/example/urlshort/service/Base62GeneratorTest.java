package com.example.urlshort.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Base62Generator 단위 테스트")
class Base62GeneratorTest {

    private Base62Generator generator;

    @BeforeEach
    void setUp() {
        generator = new Base62Generator();
    }

    @Test
    @DisplayName("generate(7)은 길이 7 문자열을 반환한다")
    void generate_returnsStringOfLength7() {
        String result = generator.generate(7);
        assertThat(result).hasSize(7);
    }

    @Test
    @DisplayName("100회 생성한 결과 모두 길이가 DEFAULT_LENGTH와 일치한다")
    void generate_100times_allHaveCorrectLength() {
        for (int i = 0; i < 100; i++) {
            String result = generator.generate(Base62Generator.DEFAULT_LENGTH);
            assertThat(result).hasSize(Base62Generator.DEFAULT_LENGTH);
        }
    }

    @Test
    @DisplayName("생성된 문자열은 [0-9A-Za-z]+ 정규식만 포함한다")
    void generate_containsOnlyBase62Characters() {
        for (int i = 0; i < 50; i++) {
            String result = generator.generate(Base62Generator.DEFAULT_LENGTH);
            assertThat(result).matches("[0-9A-Za-z]+");
        }
    }

    @ParameterizedTest(name = "length={0} 일 때 길이가 정확하다")
    @ValueSource(ints = {1, 5, 12})
    @DisplayName("다양한 길이에서도 길이가 정확하다")
    void generate_variousLengths_returnsCorrectLength(int length) {
        String result = generator.generate(length);
        assertThat(result).hasSize(length);
    }

    @Test
    @DisplayName("같은 인스턴스로 100회 생성해도 중복 비율이 매우 낮다 (unique >= 95)")
    void generate_100times_lowCollisionRate() {
        Set<String> results = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            results.add(generator.generate(Base62Generator.DEFAULT_LENGTH));
        }
        assertThat(results.size()).isGreaterThanOrEqualTo(95);
    }
}
