# URL 단축기 (url-short)

단축 URL 서비스와 클릭 이벤트 집계 파이프라인을 함께 담은 멀티모듈 프로젝트입니다.
긴 URL을 짧은 코드로 바꾸고, 리다이렉트 트래픽을 집계해 "많이 조회되는 키"만 로컬 캐시에 담습니다.

## 기술 스택

| 항목 | 내용 |
|------|------|
| Framework | Spring Boot 3.4.1 |
| Language | Java 21 |
| Build | Gradle (Kotlin DSL) 멀티모듈 |
| Database | MySQL 8.x (Primary/Replica GTID 복제) |
| Cache | L1(Caffeine) + L2(Redis), Pub/Sub 무효화 + 핫키 admission |
| Streaming | Kafka (topic `events`) |
| Analytics | ClickHouse (Kafka Engine → MV → AggregatingMergeTree) |
| Frontend | React 18 + Vite, Nginx 정적 서빙 |

## 아키텍처

```
React ──▶ Nginx :80 ─┬─ /api/urls*  ──▶ url-api   ──▶ MySQL Primary / Replica
                     ├─ /api/stats* ──▶ dashboard ──▶ ClickHouse
                     └─ /{code}     ──▶ redirect-1 | redirect-2
                                          │  L1(Caffeine) → L2(Redis) → MySQL Replica
                                          │
                                          └─▶ Kafka topic `events`
                                                 └─▶ ClickHouse Kafka Engine (group `archiver`)
                                                        └─▶ MV → AggregatingMergeTree
                                                               └─▶ dashboard 랭킹 쿼리(주기)
                                                                      └─▶ Redis Pub/Sub `url-cache:hotkeys`
                                                                             └─▶ redirect L1 적재 대상 갱신
```

### 모듈

| 모듈 | 역할 |
|------|------|
| `common` | 도메인·DTO·영속·Snowflake ID·DataSource 라우팅·캐시 채널 상수 |
| `url-api` | URL 생성/목록/삭제, L2 선입력(Write-Through), 무효화 발행, 만료 정리 |
| `redirect` | 리다이렉트 읽기 경로(L1+L2+Single-flight), 핫키 admission, 클릭 이벤트 발행 |
| `dashboard` | ClickHouse 랭킹 → 핫키 발행, 클릭수 조회 API |

### 설계 포인트

- **핫키 admission**: L1에 조건 없이 담으면 캐시 크기가 URL 총량에 정비례한다. dashboard가 실측 랭킹으로 뽑은 상위 키만 L1에 들인다. 목록을 아직 못 받은 인스턴스는 전부 허용해 콜드 스타트를 피한다.
- **상한은 분포와 예산 중 작은 쪽**: `top_n = min(전체 키 × ratio, 힙 예산 / 엔트리 크기)`. 키가 늘어도 예산이 상한을 잡는다.
- **중복 집계 차단**: Kafka Engine은 at-least-once라 재소비가 발생한다. 발행 시 실어 보낸 `event_id`로 `uniqState` 집계해 중복을 원천 차단한다.
- **클릭 이벤트는 응답 경로 밖**: 발행 실패가 리다이렉트 302를 막지 않는다.
- **삭제 직후 복제 지연 방어**: 삭제 시 Redis에 짧은 TTL의 묘비 키(`url:gone:{code}`)를 남긴다. 그 구간에 캐시 미스가 Replica의 옛 행을 읽어도 캐시에 담지 않고 버린다. 담아버리면 무효화 메시지는 이미 지나갔으므로 L2 TTL(1h) 내내 삭제된 URL이 계속 302된다.
- **핫키 갱신은 증분(delta)**: 목록의 대부분은 주기마다 그대로다. 통째로 교체하면 그 순간 L1이 비어 히트율이 바닥을 치므로 `add`/`remove`만 보낸다. `baseVersion`이 어긋나면(=메시지 유실) 적용하지 않고, Redis에 상시 보관된 최신 전체 목록(`url-cache:hotkeys:snapshot`)에서 즉시 복구한다. 새로 뜬 인스턴스도 같은 경로로 기동 직후 목록을 맞춘다.
- **Pub/Sub 채널 두 개**: 무효화(정확성, 즉시·단건)와 핫키 갱신(성능, 주기·목록).
- **CQRS Primary/Replica**: 쓰기는 Primary, 읽기는 Replica. 생성 직후 조회는 L2 선입력이 흡수해 Replication Lag 구간의 404를 막는다.

## 실행 방법

```bash
# 프론트 빌드 산출물이 Nginx에 마운트되므로 먼저 빌드한다
cd web && npm install && npm run build && cd ..

docker compose up -d --build
```

| 서비스 | 주소 |
|--------|------|
| 웹 UI (Nginx) | http://localhost |
| url-api | http://localhost:8082 (Swagger: `/swagger-ui.html`) |
| redirect-1 / redirect-2 | http://localhost:8080 / http://localhost:8081 |
| dashboard | http://localhost:8083 (Swagger: `/swagger-ui.html`) |
| ClickHouse | http://localhost:8123 |
| Grafana / Prometheus | http://localhost:3000 / http://localhost:9090 |

Grafana 대시보드: 캐시 계층, CQRS 격리, 무효화 전파, 생성직후 404, **핫키 admission**(신규).

### E2E 검증

전체 흐름(생성 → 리다이렉트 → Kafka 적재 → ClickHouse 집계 → 핫키 발행 → L1 반영 → 무효화)을 한 번에 확인합니다.

```bash
./scripts/e2e.sh
```

## API 사용 예

```bash
# 생성
curl -X POST http://localhost/api/urls \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://www.example.com/some/very/long/path"}'
# {"shortCode":"aB3xK9d","shortUrl":"http://localhost/aB3xK9d","originalUrl":"..."}

# 목록
curl http://localhost/api/urls

# 리다이렉트
curl -i http://localhost/aB3xK9d          # 302 + Location

# 삭제 (전 redirect 인스턴스 캐시 즉시 무효화)
curl -i -X DELETE http://localhost/api/urls/aB3xK9d

# 클릭수
curl http://localhost/api/stats/top?limit=20
curl http://localhost/api/stats/aB3xK9d
curl http://localhost/api/stats/hotkeys   # 마지막 핫키 발행 상태
```

## 주요 환경 변수

### 공통

| 변수명 | 기본값 | 설명 |
|--------|--------|------|
| `APP_DATASOURCE_PRIMARY_URL` / `_USERNAME` / `_PASSWORD` | localhost:3306 | 쓰기용 Primary DB |
| `APP_DATASOURCE_REPLICA_URL` / `_USERNAME` / `_PASSWORD` | Primary와 동일 | 읽기용 Replica DB |
| `SPRING_DATA_REDIS_HOST` / `_PORT` | `localhost` / `6379` | L2 캐시 및 Pub/Sub |

### url-api

| 변수명 | 기본값 | 설명 |
|--------|--------|------|
| `APP_BASE_URL` | `http://localhost` | 단축 URL 생성 시 베이스 URL |
| `APP_CACHE_PRIME_TTL` | `10s` | L2 선입력 TTL |
| `APP_CACHE_PRIME_ENABLED` | `true` | 선입력 사용 여부(검증용 토글) |
| `APP_URL_TTL_DAYS` | `7` | 단축 URL 유효기간 |

### redirect

| 변수명 | 기본값 | 설명 |
|--------|--------|------|
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | 클릭 이벤트 브로커 |
| `APP_KAFKA_CLICK_TOPIC` | `events` | 클릭 이벤트 토픽 |
| `APP_CACHE_LOCAL_MAX_SIZE` | `10000` | L1 최대 엔트리 |
| `APP_CACHE_LOCAL_TTL` / `APP_CACHE_L2_TTL` | `5m` / `1h` | L1/L2 TTL |
| `APP_CACHE_L1_ENABLED` | `true` | L1 사용 여부(런타임 토글 `/actuator/l1cache`) |

### dashboard

| 변수명 | 기본값 | 설명 |
|--------|--------|------|
| `APP_CLICKHOUSE_URL` | `jdbc:clickhouse://localhost:8123/default` | ClickHouse 접속 |
| `APP_HOTKEY_RATIO` | `0.10` | 상위 몇 비율을 핫키로 볼지 |
| `APP_HOTKEY_HEAP_BUDGET_BYTES` | `107374182` | L1에 허용할 힙 예산 |
| `APP_HOTKEY_BYTES_PER_ENTRY` | `536` | 엔트리 1건 실측 점유 |
| `APP_HOTKEY_INTERVAL_MS` | `300000` | 핫키 재계산 주기 |

## 알려진 제약

- **핫키 admission은 히트율을 내주고 메모리를 얻는 교환이다.** 상위 키만 L1에 들이므로 L1 히트율은 전량 적재 대비 낮아지고, 그만큼 미스가 L2(Redis)로 내려간다. 대신 캐시 크기가 URL 총량이 아니라 우리가 정한 상한을 따른다. `k6/redirect-bench.js`는 상위 20%에 트래픽을 몰지만 dashboard는 상위 10%를 발행하므로, 측정 구간이 스냅샷 도착 시점을 걸치면 히트율에 계단이 생긴다. 재현 가능한 수치를 원하면 `APP_HOTKEY_RATIO`를 벤치의 hot set 비율에 맞추거나, 스냅샷이 안정된 뒤 측정을 시작하라.
- **액추에이터 런타임 토글에 인증이 없다.** `/actuator/l1cache`, `/actuator/invalidation`, `/actuator/writeguard`는 벤치·검증용 스위치이고 k6 스크립트가 직접 호출한다. Nginx(:80)로는 닿지 않지만 8080~8083 포트로는 열려 있다. 외부에 노출되는 환경으로 옮긴다면 `management.server.port`를 별도 포트로 분리하고 그 포트를 publish하지 않는 것이 가장 싸다(스크립트 수정 불필요).
- **Nginx는 요청 시점에 백엔드를 DNS 재해석한다.** `upstream` 블록과 정적 호스트명은 기동 시 한 번만 해석해서, 백엔드 컨테이너를 재배포하면 옛 IP로 계속 보낸다. `resolver` + 변수 `proxy_pass`로 바꿔 재시작 없이 재배포가 반영되게 했다(반영까지 최대 10초).
- **포트가 `0.0.0.0`에 게시된다.** MySQL·Redis·Kafka·ClickHouse·Toxiproxy 제어 API·Grafana까지 호스트 전체에 열린다. 로컬 전용 스택이라 그대로 두었다. 공용 네트워크에서 쓸 일이 생기면 각 `ports`에 `127.0.0.1:` 접두사를 붙이면 된다.

## 부하 테스트

`k6/` 아래 시나리오가 있습니다. 서비스가 분리되어 생성/삭제는 `WRITE_URL`(기본 `http://localhost:8082`),
리다이렉트는 `BASE_URL`(기본 `http://localhost:8080`)로 나뉩니다.

```bash
k6 run k6/redirect-bench.js
k6 run -e PROP=on k6/invalidation-verify.js
```

### 핫키 admission A/B

히트율을 얼마나 내주고 메모리를 얼마나 아끼는지 대조한다. 스냅샷이 도착한 뒤 측정을 시작하므로
결과에 계단이 생기지 않는다.

```bash
k6 run -e MODE=all       k6/hotkey-admission.js   # 대조군: 전량 적재
k6 run -e MODE=admission k6/hotkey-admission.js   # 실험군: 상위 10%만 적재
```

메모리 차이는 Grafana `url-short · 핫키 admission` 대시보드의 JVM 힙 패널에서 본다.
런타임 토글도 가능하다.

```bash
curl -X POST localhost:8080/actuator/l1cache -H 'Content-Type: application/json' -d '{"admission":false}'
```

### VisualVM / JConsole

각 서비스에 JMX가 열려 있다. VisualVM에서 `Add JMX Connection` 으로 붙는다.

| 서비스 | JMX |
| --- | --- |
| redirect-1 | `127.0.0.1:9010` |
| redirect-2 | `127.0.0.1:9011` |
| url-api | `127.0.0.1:9012` |
| dashboard | `127.0.0.1:9013` |

redirect는 `-Xmx1024m -XX:+UseG1GC`로 고정돼 있어 힙 곡선과 GC 구간이 그대로 보인다.
Sampler → Memory 로 `ConcurrentHashMap$Node` retained 크기를 보면 admission on/off 차이가 드러난다.
