# 성능 모니터링 & 벤치마크 (Redis 단독 vs 레이어드 캐시)

리다이렉트 경로에서 **Redis 단독 캐시**와 **레이어드 캐시(L1 Caffeine + L2 Redis)** 의 성능을
Prometheus + Grafana로 측정·비교하고, k6로 부하를 준다.

> 캐시 **무효화 정확성**(삭제 후 Stale 응답이 사라지는가)의 검증은 아래 [캐시 무효화 검증](#캐시-무효화-검증-2인스턴스--pubsub) 참고. 위 벤치는 '성능', 아래는 '정확성'이다.

## 구성 요소

| 구성 | 위치 | 설명 |
|------|------|------|
| 앱 계측 | Actuator + Micrometer | `/actuator/prometheus`로 지표 노출 |
| L1 런타임 토글 | `/actuator/l1cache` | 재시작 없이 Redis 단독 ↔ 레이어드 전환 |
| Prometheus | `docker-compose` (`:9090`) | 앱 지표 2초 간격 스크레이프 |
| Grafana | `docker-compose` (`:3000`) | 대시보드 자동 프로비저닝 |
| k6 | `k6/redirect-bench.js` | 핫키 편중 부하 + 모드 전환 |

## 실행 순서

### 1) 인프라 기동 (MySQL / Redis / Prometheus / Grafana)

```bash
docker compose up -d mysql-primary mysql-replica redis redis-exporter prometheus grafana
# (선택) 실전 네트워크 지연 주입까지 쓰려면 toxiproxy도 함께: ... redis toxiproxy redis-exporter ...
```

### 2) 앱 실행 (로컬)

```bash
./gradlew bootRun
```

> Prometheus는 기본적으로 호스트의 `host.docker.internal:8080`을 스크레이프한다(로컬 앱 기준).
> 앱까지 docker-compose로 띄우려면 `monitoring/prometheus.yml`의 타깃을 `app:8080`으로 바꾼다.

### 3) 벤치마크 실행 (두 모드 순차)

```bash
./k6/run-benchmark.sh
```

- 레이어드 → 20초 간격 → Redis 단독 순으로 각각 부하를 준다.
- 개별 실행:
  ```bash
  MODE=layered k6 run k6/redirect-bench.js
  MODE=redis   k6 run k6/redirect-bench.js
  ```

### 4) Grafana에서 비교

- http://localhost:3000 (anonymous Admin 허용, 또는 admin/admin)
- 대시보드: **URL Shortener — Redis vs Layered Cache**
- 그래프의 **앞 구간 = 레이어드, 뒤 구간 = Redis 단독**이다. 지연 백분위(p50/p95/p99)·처리량·계층별 서빙을 두 구간으로 비교한다.
- 개별 스샷을 원하면 시간범위를 각 봉우리로 확대하거나, 모드별로 따로 실행(`MODE=layered` / `MODE=redis`)한다.

### (선택) 실전 네트워크 지연 주입 — '원격 Redis' 흉내

로컬은 Redis가 루프백(RTT ~수백 µs)이라 절대값이 작다. 실제 원격 Redis처럼 만들려면 toxiproxy로 app↔Redis에 지연을 주입한다.

```bash
docker compose up -d toxiproxy               # 앱↔Redis 프록시(:6380)
SPRING_DATA_REDIS_PORT=6380 ./gradlew bootRun # 앱을 프록시 경유로 실행
./k6/set-redis-latency.sh 3 1                 # 3ms + 지터 1ms (cross-AZ 수준)
./k6/run-benchmark.sh                         # 이후 벤치는 이 지연을 반영
./k6/set-redis-latency.sh 0                   # 지연 제거
```

### 그래프 잔상 초기화

`run-benchmark.sh`는 실행 시 이전 벤치 데이터를 자동 삭제한다(`RESET=false`로 끔). 수동으로는:

```bash
./k6/reset-metrics.sh    # Prometheus TSDB에서 벤치 지표 삭제 (admin API 필요)
```

## 주요 지표

| 지표군 | Prometheus 메트릭 | 의미 |
|------|-------------------|------|
| 응답 시간 | `http_server_requests_seconds_bucket{status="302"}` | p50/p95/p99 |
| 처리량 | `http_server_requests_seconds_count{status="302"}` | TPS |
| 계층별 서빙 | `urlcache_hits_total{layer="l1\|l2"}`, `urlcache_loads_total` | L1/L2/DB 서빙 분포 |
| 계층별 적중률 | `urlcache_hits_total{layer} / urlcache_requests_total{layer}` | L1·L2 각각의 적중률 |
| 네트워크 절감 | `urlcache_hits_total{layer="l1"} / urlcache_requests_total{layer="l1"}` | Redis 네트워크 회피율(L1 흡수) |
| 앱 자원 | `process_cpu_usage`, `jvm_memory_used_bytes`, `jvm_gc_pause_seconds_*` | CPU·메모리·GC |
| Redis 부하 | `redis_commands_processed_total`, `redis_net_*_bytes_total`, `redis_connected_clients`, `redis_memory_used_bytes` | ops/s·네트워크·clients·메모리 |
| 현재 모드 | `urlcache_l1_enabled` | 1=레이어드, 0=Redis 단독 |

## k6 파라미터 (환경변수)

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `MODE` | `layered` | `layered` \| `redis` |
| `KEYS` | `1000` | 시드할 단축 URL 수 |
| `HOT_PCT` | `0.2` | 핫키로 취급할 상위 비율 |
| `HOT_TRAFFIC` | `0.8` | 핫키로 향하는 트래픽 비율 |
| `VUS` | `50` | 가상 유저 수 |
| `DURATION` | `60s` | 부하 지속 시간 |

## 토글 엔드포인트 직접 호출

```bash
# 현재 모드 확인
curl -s localhost:8080/actuator/l1cache
# Redis 단독으로 전환(L1 off)
curl -s -X POST localhost:8080/actuator/l1cache -H 'Content-Type: application/json' -d '{"enabled": false}'
# 레이어드로 복귀(L1 on)
curl -s -X POST localhost:8080/actuator/l1cache -H 'Content-Type: application/json' -d '{"enabled": true}'
```

---

# 캐시 무효화 검증 (2인스턴스 + Pub/Sub)

성능이 아니라 **정확성**을 검증한다: *"한 인스턴스에서 URL을 삭제하면, 다른 인스턴스의 L1까지 Pub/Sub로 즉시 비워져 Stale 응답이 사라지는가?"* — 즉 노션 글의 핵심 주장을 실측한다.

## 왜 이렇게 설계했나 (핵심 3가지)

1. **k6가 "정답지(oracle)"다.** k6는 자기가 방금 삭제한 키를 알기 때문에, 삭제 이후 그 키가 404가 아니면 Stale이라고 판정할 수 있다. 앱 지표(발행/수신 카운트)는 "기계가 돌았다"만 알려줄 뿐 "Stale을 안 줬다"는 증명이 못 된다 → 합격/불합격은 k6 threshold로 못박는다.
2. **인스턴스가 2개여야 Pub/Sub를 검증한다.** 단일 인스턴스면 `delete()`가 자기 L1을 동기적으로 지워 Stale이 아예 안 생긴다. `app`(A, 발행)에서 삭제하고 `app2`(B, 수신)를 관측해야 L1 분기가 드러난다.
3. **음성대조군(전파 토글)이 있어야 증명이 된다.** `POST /actuator/invalidation {"enabled": false}`로 B의 전파를 끄면 "Pub/Sub 없는/메시지 놓친 인스턴스"가 재현된다. **ON=즉시 404 수렴 / OFF=TTL(5분)까지 Stale** 대비가 Pub/Sub의 가치를 보여준다.

> **replica-lag 혼입 제거:** 삭제는 Primary, 조회는 Replica라 복제 지연 동안 A·B 둘 다 302를 줄 수 있다(캐시 아님). 그래서 **"B는 302인데 A는 이미 404"일 때만** 진짜 L1 분기(`stale_reads`)로 세고, 둘 다 302면 `db_lag_reads`로 분리 집계한다.

## 실행 순서

```bash
# 1) 2개 인스턴스 + 모니터링 스택 기동 (app=8080, app2=8081)
docker compose up -d --build mysql-primary mysql-replica redis toxiproxy redis-exporter prometheus grafana app app2

# 2) 무효화 검증 (전파 ON → OFF 순차, 재적재 레이스까지 하려면 RACE=true)
./k6/run-invalidation.sh
RACE=true ./k6/run-invalidation.sh

# 3) Grafana → 'URL Shortener — Cache Invalidation'
```

개별 실행:

```bash
BASE_A=http://localhost:8080 BASE_B=http://localhost:8081 PROP=on  k6 run k6/invalidation-verify.js  # 합격 기대: stale_reads==0
BASE_A=http://localhost:8080 BASE_B=http://localhost:8081 PROP=off k6 run k6/invalidation-verify.js  # 대조군: stale_reads>0
TEST=race k6 run k6/invalidation-verify.js                                                             # 재적재: repopulation==0
```

## 판정 지표 (k6 + threshold)

| 지표 | 의미 | 합격 기준 |
|------|------|-----------|
| `converged` | 삭제된 키가 B에서 결국 404로 수렴한 비율 | 전파 ON: `>0.99`(수렴) / OFF: `<0.01`(미수렴=Stale 지속) |
| `repopulation` | 404를 본 뒤 302 재출현 (읽기/삭제 재적재 레이스) | **항상 `==0`** |
| `stale_window_ms` | 삭제 → B 첫 404 까지 (수렴 시간) | 전파 ON: `p95<2000ms`(유계) — 폴 간격이 하한이라 절대값보다 OFF의 무한 지속과 대비가 요점 |
| `stale_reads` | 삭제 후 B가 A와 분기해(=A는 404) 준 Stale 302 | ON은 전파 윈도우 동안 소량 발생이 **정상**(즉시 0 아님). OFF는 지속 누적 |
| `db_lag_reads` | A·B 둘 다 302 (복제 지연, 캐시 아님) | 분리 집계 — 판정에서 제외 |

> **실측 예시(스모크):** 전파 **ON** → `converged=100%`, `stale_window_ms p95≈91ms`, `repopulation=0`, `stale_reads`는 전파 윈도우로 소량. 전파 **OFF** → `converged=0%`, `stale_reads` 대량 누적(L1이 안 비워져 TTL까지 Stale). ON/OFF 대비가 Pub/Sub 무효화의 효과다.

## 관측 지표 (앱, 서사 레이어)

| 지표 | 의미 |
|------|------|
| `urlcache_invalidations_published_total` | 발행 인스턴스가 무효화를 발행한 횟수 |
| `urlcache_invalidations_received_total{instance}` | 각 인스턴스가 수신·적용(L1 evict)한 횟수 — 발행과의 갭 = 미전파/유실 |
| `urlcache_invalidation_propagation_enabled{instance}` | 전파 적용 상태(1/0) — 음성대조군 구간 구분 |

## 무효화 전파 토글 엔드포인트

```bash
curl -s localhost:8081/actuator/invalidation                                                        # 상태 확인
curl -s -X POST localhost:8081/actuator/invalidation -H 'Content-Type: application/json' -d '{"enabled": false}'  # 전파 끄기(대조군)
curl -s -X POST localhost:8081/actuator/invalidation -H 'Content-Type: application/json' -d '{"enabled": true}'   # 복귀
```

## 한계 (정직하게)

- **`stale_window_ms`는 정밀 전파시간이 아니다.** 폴 간격(기본 20ms)이 하한이라, 흥미로운 건 절대값이 아니라 "유한 시간에 0으로 수렴(ON) vs TTL까지 지속(OFF)"이라는 정성적 대비다.
- **전파 토글은 메시지 유실의 *효과*를 재현**(수신해도 evict 생략)하는 것이지, TCP 메시지를 실제로 떨어뜨리는 건 아니다. 진짜 커넥션 단절 실험이 필요하면 toxiproxy로 구독 커넥션을 끊어 확장할 수 있다.
