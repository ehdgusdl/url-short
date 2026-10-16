# 022. Redis 커맨드 타임아웃, L2 Circuit Breaker, DB 로더 세마포어 Bulkhead

- 상태: 채택
- 날짜: 2026-10-16
- 근거 커밋: 미커밋(작업 트리). 측정 기록은 [실행 계획](../../exec-plans/completed/2026-10-16-redis-failure-isolation.md)

## 맥락
[010](010-cache-miss-path-hardening.md)은 Redis가 예외를 즉시 던질 때만 막았다. Redis가 응답하지 않거나 연결이 끊기면 예외가 오기까지 Lettuce 기본 커맨드 타임아웃(60초)을 기다린다(끊긴 동안 커맨드는 재연결 버퍼에 쌓인다).
L1 미스 1건이 Redis를 최대 4번(L2 조회, 음성 캐시 확인, 묘비 확인, L2 적재) 부르므로 요청 스레드가 묶이고, Tomcat 스레드가 바닥나면 Redis를 안 쓰는 L1 히트까지 대기열에서 멈춘다.

실측(nginx 경유 300 req/s, 90% L1 히트): Redis 무응답·끊김 시 L1 히트 p99도 60초, 에러율(nginx 504) 45%, k6가 보내지 못한 요청 1.7만 건. [실패 008](../failures/008-redis-hang-stalls-l1-hits.md)

## 결정
1. `spring.data.redis.timeout: 100ms`(redirect, `SPRING_DATA_REDIS_TIMEOUT`). 로컬 Redis 왕복은 1ms 미만이라 정상 경로에 영향이 없다.
2. L2 호출 5곳을 resilience4j `CircuitBreaker`로 감싼다(`CacheConfig.l2CircuitBreaker`). 최근 50회 중 실패가 50% 이상이면 열고, 5초 뒤 5회만 시험한다. 느린 호출 기준은 두지 않는다: 타임아웃이 느린 Redis를 이미 실패로 바꾸고, 앱 쪽 측정 시간에는 CPU 포화·GC 정지가 섞여 Redis가 멀쩡해도 열릴 수 있다(리뷰 지적). 열린 동안의 거절도 기존 catch로 와서 로더로 내려간다. 게이지 `urlcache.l2.breaker.state`(0 닫힘, 1 열림, 2 반열림).
3. DB 로더(`RedirectService.loadFromDb`)에 `java.util.concurrent.Semaphore` Bulkhead. 동시 10개(`APP_DB_BULKHEAD_MAX_CONCURRENT`, Replica Hikari 풀 기본값과 같다), 50ms(`APP_DB_BULKHEAD_WAIT`) 안에 못 얻으면 `DbBusyException` → 503. 카운터 `urlcache.db.rejected`.

## 버린 대안
- 타임아웃 단독: 무응답 대기는 60초 → 100ms로 줄지만 장애 내내 L1 미스마다 최대 4 × 100ms를 낸다. 지연 모드(Redis 응답 1초)에서도 매 요청이 타임아웃을 끝까지 기다린다.
- CB 단독(타임아웃 기본값): 차단기가 열리려면 실패가 쌓여야 하는데 실패 하나가 60초 걸린다. 그 사이 스레드가 이미 바닥난다.
- CB만 두고 Bulkhead 없음: 차단기가 열리면 L1 미스가 전부 DB로 간다. Hikari는 풀이 차면 connectionTimeout(30초)까지 스레드를 붙잡아 같은 증상(스레드 고갈)이 DB 쪽에서 재발한다. 아래 DB 집중 측정 참고.
- resilience4j Bulkhead 모듈: 세마포어 + tryAcquire면 충분해서 의존성을 늘리지 않았다. CB는 상태 전이·슬라이딩 윈도·느린 호출 판정을 직접 짜면 길어져서 코어 모듈(`resilience4j-circuitbreaker`)만 쓴다. Spring Boot starter·어노테이션은 쓰지 않는다.
- Redis Cluster/Sentinel: Redis 노드 장애에 대한 가용성이다. 네트워크 지연·단절, 느린 명령처럼 노드가 살아 있어도 생기는 문제는 그대로 남는다. 단일 Redis 로컬 구성에서 근거도 없다.

## 결과
같은 조건(nginx 경유 300 req/s 90초, 90% L1 히트, 10% 없는 코드)에서 전체 p99:

| Redis 상태 | Before | After (Before와 같은 절차) | After 최종 설정 (60초 예열 후) |
|---|---|---|---|
| 지연(응답 1초) | 3.18초 | 105ms | 51ms |
| 무응답(timeout toxic) | 60.0초, 에러 45.6% | 163ms, 에러 0% | 52ms, 에러 0% |
| 연결 끊김(proxy down) | 60.1초, 에러 44.5% | 164ms, 에러 0% | 51ms, 에러 0% |
| 정상(JVM 예열 후) | - | 120ms | - |

가운데 열은 느린 호출 기준(50ms)이 있던 설정이다. 리뷰 지적으로 그 기준을 뺀 최종 설정을 다시 쟀다(오른쪽). 장애 모드는 모두 타임아웃으로 실패가 되므로 차단기 동작은 같고, 차이는 주로 예열 여부다.

DB 집중(Redis 끊김, 1000 req/s 전부 L1 미스): Bulkhead 끔(상한 1000) p99 10.6초, 중앙값 4.3초, 미발송 2.5만 건 / 켬 p99 2.5초, 중앙값 1.9ms, 17.8%를 503으로 즉시 거절.

남는 한계: 차단기가 열린 동안 묘비·음성 캐시 확인도 건너뛴다. 복제 지연 구간에 삭제된 키가 L1에 담길 수 있다. Redis 장애 때의 기존 동작과 같지만, 차단기는 Redis가 회복된 뒤에도 최대 5초 더 열려 있어 그 창이 조금 넓어진다. 같은 키의 대기 요청(SingleFlight follower)은 leader가 Bulkhead에 거절되면 함께 503을 받는다. 핫키 리스너와 dashboard의 Redis 호출은 차단기 밖이다.
