# Redis 장애 격리와 구독 재연결 시 L1 비우기 (2026-10-16 완료)

## 목적
- A. Pub/Sub 무효화 구독이 끊긴 사이 삭제된 URL이 L1에서 계속 302되는 구간을 줄인다.
- B. Redis가 느리거나 죽어도 리다이렉트가 묶이지 않게 한다.

결정: [ADR 021](../../design-docs/decisions/021-flush-l1-on-invalidation-resubscribe.md), [ADR 022](../../design-docs/decisions/022-redis-timeout-breaker-db-bulkhead.md). 실패: [008](../../design-docs/failures/008-redis-hang-stalls-l1-hits.md), [009](../../design-docs/failures/009-missed-invalidation-served-for-l1-ttl.md).

## 추가한 것
- `CacheInvalidationListener`가 `SubscriptionListener` 구현, `LayeredUrlCache.evictLocalOnResubscribe`
- redirect `spring.data.redis.timeout: 100ms`
- `CacheConfig.l2CircuitBreaker`(resilience4j-circuitbreaker 2.2.0), L2 호출 5곳을 감쌈
- `RedirectService` DB 로더 세마포어 Bulkhead, `DbBusyException`(503)
- 지표: `urlcache.invalidations.received{scope="resubscribe"}`, `urlcache.l2.breaker.state`, `urlcache.db.rejected`
- `k6/redis-fault.js`, `k6/run-redis-fault.sh`, `k6/run-stale-after-reconnect.sh`

## 측정 조건
- 로컬 docker compose 전체 스택. redirect 2대(각 1 vCPU, Xmx 1g), Redis 앞에 toxiproxy(`redis` 프록시는 redirect만 거친다. url-api는 Redis 직결).
- 로컬 포트 충돌로 redirect-1 호스트 포트만 18080으로 바꾼 오버라이드를 얹었다(레포 미커밋).
- 측정 중 두 redirect의 admission을 끄고(전량 L1 적재) 끝나면 되돌렸다. toxiproxy도 매 실행 후 원복.

### A. 구독 끊김 stale (`k6/run-stale-after-reconnect.sh 10`)
URL 생성 → 두 인스턴스 L1 적재 → toxiproxy `redis` 비활성화 → url-api로 삭제 → 10초 뒤 재활성화 → 0.5초마다 양쪽 조회해 둘 다 404가 될 때까지.

### B. Redis 장애 (`k6/run-redis-fault.sh`)
nginx(:80) 경유 300 req/s 고정 도착률 90초. 90%는 미리 데운 200개 키(L1 히트), 10%는 없는 랜덤 코드(L1 미스 → 음성 캐시 확인 → DB → 404).
모드: none(장애 없음), latency(Redis 응답마다 1초), timeout(timeout toxic, 응답 없이 붙잡음), down(프록시 비활성화).
k6 요청 타임아웃 70초, nginx proxy_read_timeout 기본 60초. 에러 = 기대 상태(302 또는 404)가 아닌 응답.

## 결과

### A
| | 삭제 → 양쪽 404 | 재연결 → 양쪽 404 |
|---|---|---|
| Before (1회) | 299.7초 | 289.5초 |
| After (3회) | 16.7 / 16.7 / 17.9초 | 6.6 / 6.6 / 7.7초 |

Before는 L1 TTL(5분)이 상한이라 1회만 쟀다. After의 16~18초 = 10초 끊김 + Lettuce 재연결 대기.

### B (전체 p99 / L1 히트 p99 / L1 미스 p99 / 에러율 / 미발송)
| 모드 | Before | After |
|---|---|---|
| none | 1080ms / 1071 / 1485 / 0% / 158 | 1134ms / 1108 / 1328 / 0.01% / 144 |
| none (JVM 예열 후 재측정) | - | 120ms / 117 / 182 / 0% / 0 |
| latency 1초 | 3175ms / 500 / 3829 / 0% / 115 | 105ms / 83 / 306 / 0% / 0 |
| timeout | 60019ms / 60016 / 60045 / 45.6% / 17628 | 163ms / 131 / 299 / 0% / 0 |
| down | 60110ms / 60129 / 60101 / 44.5% / 17556 | 164ms / 136 / 306 / 0% / 0 |

| latency 1초 (최종 설정, 60초 예열 후) | - | 51ms / 35 / 214 / 0% / 0 |
| timeout (최종 설정) | - | 52ms / 33 / 224 / 0% / 0 |
| down (최종 설정) | - | 51ms / 35 / 209 / 0% / 0 |

최종 설정은 리뷰 지적으로 차단기의 느린 호출 기준(50ms)을 뺀 것이다. 재빌드 후 60초 예열하고 쟀다.
첫 none은 Before·After 모두 기동 직후 첫 실행이라 JIT 예열 구간이 섞였다. 예열 후 기준선은 120ms.
After 장애 중 차단기는 OPEN → 5초 뒤 HALF_OPEN → 시험 실패 → OPEN을 반복했다.

### B 보조: Bulkhead (Redis down, 1000 req/s 전부 L1 미스, 60초)
| | 전체 p99 | 중앙값 | 503 거절 | 미발송 |
|---|---|---|---|---|
| Bulkhead 끔(상한 1000) | 10.6초 | 4.3초 | 0 | 25,083 |
| Bulkhead 켬(상한 10, 50ms) | 2.5초 | 1.9ms | 10,512 (17.8%) | 923 |

300 req/s 기본 시나리오에서는 DB로 가는 요청이 초당 30건이라 Bulkhead 거절이 0건이었다. B의 p99 개선은 타임아웃과 차단기 몫이다.

## 알게 된 것
- 연결이 끊긴 상태(down)도 무응답(timeout)과 똑같이 60초를 기다렸다. Lettuce가 재연결 동안 커맨드를 버퍼에 쌓기 때문이다.
- Redis를 안 쓰는 L1 히트도 Tomcat 스레드 고갈로 같이 멈췄다. "Redis 장애 시 핫키는 L1이 흡수한다"는 가정은 타임아웃이 짧을 때만 성립한다.

## 미해결
[tech-debt](../tech-debt.md): 재연결 대기 6~8초, 차단기 밖의 Redis 호출, 차단기 열림 중 묘비 확인 생략, DB 집중 시 p99 2.5초.
