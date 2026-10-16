# 009. 구독이 끊긴 사이 삭제된 URL을 L1 TTL 동안 302

- 발견: 2026-10-16 `k6/run-stale-after-reconnect.sh` 측정
- 고친 커밋: 미커밋(작업 트리)
- 관련 결정: [ADR 021](../decisions/021-flush-l1-on-invalidation-resubscribe.md)

## 무엇이 깨졌나
redirect↔Redis를 10초 끊은 사이 삭제한 URL을, 두 redirect 인스턴스가 삭제 후 299.7초 동안 302로 응답했다.

## 왜 그렇게 됐나 (근본 원인)
Pub/Sub는 끊긴 동안의 메시지를 다시 주지 않는다. 재연결 후에도 놓친 무효화를 메울 장치가 없어 L1 TTL(5분)이 유일한 상한이었다.

## 어떻게 고쳤나
구독 확인을 받을 때마다 L1을 비운다.

## 재발 방지
redirect `CacheInvalidationListenerTest.clears_l1_when_the_subscription_comes_back`, 수동 `k6/run-stale-after-reconnect.sh`.
