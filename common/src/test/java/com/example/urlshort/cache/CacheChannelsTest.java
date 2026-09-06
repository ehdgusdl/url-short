package com.example.urlshort.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("캐시 키 접두사 규약")
class CacheChannelsTest {

    @Test
    @DisplayName("묘비 접두사는 값 접두사 아래에 있으면 안 된다")
    void tombstone_prefix_must_not_live_under_the_value_prefix() {
        // 전체 무효화는 KEY_PREFIX + "*" 를 훑어 지운다. 묘비가 그 아래 있으면
        // 살아 있는 묘비까지 지워져, 삭제된 URL이 복제 지연 구간에 캐시로 되살아난다.
        assertThat(CacheChannels.TOMBSTONE_PREFIX).doesNotStartWith(CacheChannels.KEY_PREFIX);
        assertThat(CacheChannels.KEY_PREFIX).doesNotStartWith(CacheChannels.TOMBSTONE_PREFIX);
    }
}
