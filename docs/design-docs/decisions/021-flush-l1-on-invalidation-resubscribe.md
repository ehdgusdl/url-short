# 021. 무효화 구독이 다시 붙으면 L1 전체 비우기

- 상태: 채택
- 날짜: 2026-10-16
- 근거 커밋: 미커밋(작업 트리). 측정 기록은 [실행 계획](../../exec-plans/completed/2026-10-16-redis-failure-isolation.md)

## 맥락
삭제 무효화는 Redis Pub/Sub(`url-cache:invalidation`)로 보낸다([003](003-layered-cache-pubsub-invalidation.md)). Pub/Sub는 구독자가 끊겨 있던 동안의 메시지를 다시 주지 않는다.
redirect↔Redis가 끊긴 사이 url-api가 삭제하면(url-api는 Redis에 직결이라 L2 삭제와 발행은 성공한다) redirect는 메시지를 못 받고, 그 키가 L1에 있으면 L1 TTL(5분)이 끝날 때까지 302를 준다.
핫키 채널은 baseVersion 불일치를 감지해 스냅샷으로 복구하지만([012](012-hotkey-distribution-delta-snapshot.md)), 무효화 채널에는 그런 장치가 없었다.

실측(10초 끊김 중 삭제): 삭제부터 두 인스턴스가 모두 404를 줄 때까지 299.7초. [실패 009](../failures/009-missed-invalidation-served-for-l1-ttl.md)

## 결정
`CacheInvalidationListener`가 `SubscriptionListener`를 구현해, 구독 확인(`onChannelSubscribed`)을 받을 때마다 L1을 통째로 비운다(세대 카운터도 올린다).
Lettuce가 재연결하면 채널을 다시 구독하고, Spring의 `RedisMessageListenerContainer`가 그 확인을 리스너에 전달한다. 기동 직후 첫 구독에서도 불리지만 그때 L1은 비어 있다.
비운 뒤 다시 채우는 것은 기존 admission 규칙(핫키만 L1)을 그대로 따른다. 카운터 `urlcache.invalidations.received{scope="resubscribe"}`.

## 버린 대안
- Redis Streams로 전환: 놓친 메시지를 다시 읽을 수 있어 끊김 중 stale까지 없앨 수 있지만, 컨슈머 그룹·오프셋·트리밍 운영이 생긴다. 끊김 중 stale은 이 결정으로도 끊김 시간만큼 남는다. 그 구간까지 0으로 만들어야 할 요구가 생기면 다시 본다.
- 끊긴 동안 지워진 키만 골라 지우기: 무엇이 지워졌는지 알려면 결국 별도 로그(Stream 등)가 필요하다.
- L1 TTL 단축: 모든 핫키의 히트율을 상시 깎아 드문 장애에 대비하는 셈이다.
- Redis Cluster/Sentinel: Redis 자체의 가용성 문제이고, 앱↔Redis 네트워크 단절에는 효과가 없다.

## 결과
같은 조건 3회 측정에서 삭제 → 양쪽 404가 16.7~17.9초(10초 끊김 + Lettuce 재연결 6.6~7.7초). 299.7초 대비 약 17배 단축.
남는 한계: 끊긴 동안은 여전히 stale이다. 상한은 끊김 시간 + 재연결 대기다. 재연결 대기는 Lettuce 재연결 백오프가 정한다([tech-debt](../../exec-plans/tech-debt.md)).
