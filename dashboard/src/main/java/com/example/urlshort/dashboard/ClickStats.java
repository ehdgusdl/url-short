package com.example.urlshort.dashboard;

/** 단축 코드별 클릭수. */
public record ClickStats(String shortCode, long clicks) {
}
