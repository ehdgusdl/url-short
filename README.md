# URL 단축기 (url-short)

Spring Boot 기반의 URL 단축 서비스입니다. 긴 URL을 짧은 코드로 변환하고, 단축 URL 접근 시 원본 URL로 리다이렉트합니다.

## 기술 스택

| 항목 | 내용 |
|------|------|
| Framework | Spring Boot 3.4.1 |
| Language | Java 21 |
| Build | Gradle (Kotlin DSL) |
| Database | MySQL 8.x |
| Cache | Caffeine (로컬 캐시) |

## 실행 방법

Docker Compose로 DB와 앱을 함께 실행합니다.

```bash
docker compose up --build
```

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

## 환경 변수

| 변수명 | 기본값 | 설명 |
|--------|--------|------|
| `APP_BASE_URL` | `http://localhost:8080` | 단축 URL 생성 시 사용할 베이스 URL |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/urlshort?...` | MySQL 접속 URL |
| `SPRING_DATASOURCE_USERNAME` | `urlshort` | DB 사용자명 |
| `SPRING_DATASOURCE_PASSWORD` | `urlshort` | DB 비밀번호 |
