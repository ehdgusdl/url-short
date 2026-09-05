package com.example.urlshort.dashboard;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 핫키 산출 파라미터.
 *
 * @param ratio          전체 키 중 상위 몇 비율을 담을지(0.10 = 상위 10%)
 * @param heapBudgetBytes L1에 허용할 힙 예산. 이 예산이 분포보다 우선하는 상한이다.
 * @param bytesPerEntry  엔트리 1건의 실측 점유(키 + 값 + 캐시 구조 오버헤드)
 * @param lookbackDays   랭킹 집계 기간
 */
@ConfigurationProperties(prefix = "app.hotkey")
public record HotKeyProperties(double ratio, long heapBudgetBytes, long bytesPerEntry, int lookbackDays) {

    /**
     * 담을 키 개수 = min(분포가 정한 목표, 예산이 정한 상한).
     * 키가 늘어도 예산이 상한을 잡아, 캐시 크기가 URL 총량에 정비례하는 것을 막는다.
     */
    public int topN(long distinctKeys) {
        long byDistribution = Math.round(distinctKeys * ratio);
        long byBudget = heapBudgetBytes / bytesPerEntry;
        return (int) Math.max(1, Math.min(byDistribution, byBudget));
    }
}
