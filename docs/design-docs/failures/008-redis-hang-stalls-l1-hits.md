# 008. Redis가 응답하지 않으면 L1 히트까지 60초 멈춤

- 발견: 2026-10-16 toxiproxy 장애 주입 측정(`k6/run-redis-fault.sh`)
- 고친 커밋: 미커밋(작업 트리)
- 관련 결정: [ADR 022](../decisions/022-redis-timeout-breaker-db-bulkhead.md)

## 무엇이 깨졌나
Redis 무응답(timeout toxic)·연결 끊김에서 nginx 경유 300 req/s 중 L1 히트(Redis를 안 부르는 경로) p99도 60초였다. 에러율 45%(nginx 504), 90초 동안 k6가 보내지 못한 요청 1.7만 건.

## 왜 그렇게 됐나 (근본 원인)
커맨드 타임아웃을 지정하지 않아 Lettuce 기본 60초를 썼다. 연결이 끊긴 동안 커맨드는 재연결 버퍼에서 타임아웃까지 기다린다. [004](004-redis-down-turns-l1-miss-into-500.md)는 예외를 삼키게 했을 뿐 예외가 늦게 오는 경우는 다루지 않았다. L1 미스가 스레드를 60초씩 잡자 Tomcat 스레드가 바닥나 L1 히트도 대기열에 섰다.

## 어떻게 고쳤나
커맨드 타임아웃 100ms, L2 호출 Circuit Breaker, DB 로더 세마포어 Bulkhead.

## 재발 방지
redirect `LayeredUrlCacheResilienceTest.stops_calling_redis_once_the_breaker_opens`, `RedirectServiceTest.rejects_loads_beyond_the_bulkhead`, 수동 `k6/run-redis-fault.sh`.
