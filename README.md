# URL 단축기 (url-short)

Spring Boot 기반의 URL 단축 서비스입니다. 긴 URL을 짧은 코드로 변환하고, 단축 URL 접근 시 원본 URL로 리다이렉트합니다.

## 기술 스택

| 항목 | 내용 |
|------|------|
| Framework | Spring Boot 3.4.1 |
| Language | Java 21 |
| Build | Gradle (Kotlin DSL) |
| Database | MySQL 8.x (Primary/Replica 복제) |
| Cache | Layered L1(Caffeine) + L2(Redis), Pub/Sub 무효화 |

## 아키텍처

Read-heavy(리다이렉트) 특성에 맞춰 읽기 성능과 다중 인스턴스 일관성을 함께 확보한 구조입니다.

- **Layered 캐시 + Pub/Sub 즉시 무효화**: L1(로컬 Caffeine)으로 네트워크 I/O 없이 응답하고, L2(Redis)를 공유 캐시로 둡니다. URL 삭제/만료 시 L1·L2를 제거한 뒤 Redis Pub/Sub로 전 인스턴스에 무효화를 브로드캐스트해, TTL 만료를 기다리지 않고 모든 서버의 로컬 캐시를 즉시 비웁니다.
- **CQRS Primary/Replica 분리**: `AbstractRoutingDataSource`로 쓰기는 Primary, 읽기는 Replica로 라우팅해 자원을 격리합니다. (`docker-compose`에는 GTID 기반 MySQL 복제 2노드가 구성되어 있습니다.)
- **Primary Fallback + Single-flight**: 생성 직후 짧은 TTL 동안 해당 키 읽기를 Primary로 라우팅해 Replication Lag 구간의 생성 직후 404(Stale)를 차단하고, 동일 키 캐시 미스가 몰릴 때 Single-flight로 DB 중복 조회를 통합합니다.

```
Client ──▶ App(L1 Caffeine) ──▶ Redis(L2) ──▶ MySQL Replica (읽기)
                  ▲                                   ▲
                  │  Pub/Sub 무효화          최근 Write는 Primary로 (Fallback)
                  └──────────── Redis ◀── MySQL Primary (쓰기) ──복제(GTID)──┘
```

## 실행 방법

Docker Compose로 MySQL Primary/Replica, Redis, 앱을 함께 실행합니다.

```bash
docker compose up --build
```

> 참고: 최초 기동 시 Replica가 Primary를 GTID auto-position으로 따라잡습니다. Primary가 healthy가 된 뒤 Replica 복제가 시작됩니다.

앱이 정상 기동되면 `http://localhost:8080` 에서 서비스를 이용할 수 있습니다.

Swagger UI: http://localhost:8080/swagger-ui.html

## API 사용 예

### 1) 단축 URL 생성

```bash
curl -X POST http://localhost:8080/api/urls \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://www.example.com/some/very/long/path"}'
```

응답 예:

```json
{
  "shortCode": "aB3xK9d",
  "shortUrl": "http://localhost:8080/aB3xK9d",
  "originalUrl": "https://www.example.com/some/very/long/path"
}
```

### 2) 단축 URL 리다이렉트

```bash
curl -i http://localhost:8080/aB3xK9d
# HTTP/1.1 302 Found
# Location: https://www.example.com/some/very/long/path
```

### 3) 단축 URL 삭제

```bash
curl -i -X DELETE http://localhost:8080/api/urls/aB3xK9d
# HTTP/1.1 204 No Content  (삭제 즉시 전 인스턴스 캐시 무효화)
```

## 환경 변수

| 변수명 | 기본값 | 설명 |
|--------|--------|------|
| `APP_BASE_URL` | `http://localhost:8080` | 단축 URL 생성 시 사용할 베이스 URL |
| `APP_DATASOURCE_PRIMARY_URL` | `jdbc:mysql://localhost:3306/urlshort?...` | 쓰기용 Primary DB 접속 URL |
| `APP_DATASOURCE_PRIMARY_USERNAME` / `_PASSWORD` | `urlshort` | Primary DB 계정 |
| `APP_DATASOURCE_REPLICA_URL` | (Primary와 동일) | 읽기용 Replica DB 접속 URL |
| `APP_DATASOURCE_REPLICA_USERNAME` / `_PASSWORD` | `urlshort` | Replica DB 계정 |
| `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` | `localhost` / `6379` | L2 캐시 및 Pub/Sub용 Redis |
| `APP_CACHE_LOCAL_TTL` / `APP_CACHE_L2_TTL` | `5m` / `1h` | L1/L2 캐시 TTL |
| `APP_CACHE_RECENT_WRITE_TTL` | `2s` | Primary Fallback 유지 시간(Replication Lag 대비) |
