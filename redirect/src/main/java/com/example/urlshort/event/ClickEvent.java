package com.example.urlshort.event;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 리다이렉트 1건 = 클릭 이벤트 1건. ClickHouse Kafka Engine이 JSONEachRow로 그대로 읽는다.
 *
 * <p>{@code eventId}는 중복 제거용이다. Kafka Engine은 at-least-once라 재시작·리밸런싱 때 같은 블록을
 * 다시 소비할 수 있어, 집계에서 {@code uniqState(event_id)}로 중복 카운트를 원천 차단한다.
 */
public record ClickEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("short_code") String shortCode,
        @JsonProperty("ts") String ts) {
}
