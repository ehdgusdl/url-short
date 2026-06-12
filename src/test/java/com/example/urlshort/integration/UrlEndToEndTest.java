package com.example.urlshort.integration;

import com.example.urlshort.cache.LayeredUrlCache;
import com.example.urlshort.domain.UrlMapping;
import com.example.urlshort.dto.CreateUrlRequest;
import com.example.urlshort.dto.CreateUrlResponse;
import com.example.urlshort.repository.UrlMappingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UrlEndToEndTest extends AbstractMySqlContainerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UrlMappingRepository repository;

    @Autowired
    private LayeredUrlCache cache;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
        // 테스트 간 캐시(L1/L2) 격리 — 잔존 캐시로 인한 Stale 결과 방지.
        cache.invalidateAll();
    }

    @Test
    void create_then_redirect_works() throws Exception {
        // POST /api/urls
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/integration");
        ResponseEntity<CreateUrlResponse> createResponse = restTemplate.postForEntity(
                "/api/urls", request, CreateUrlResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        CreateUrlResponse body = createResponse.getBody();
        assertThat(body).isNotNull();
        assertThat(body.shortCode()).hasSize(7);
        assertThat(body.shortUrl()).startsWith("http://");

        // DB에 1건 저장 확인
        assertThat(repository.findAll()).hasSize(1);

        // redirect 검증 — redirect를 따라가지 않는 HttpClient 사용
        String shortUrl = "http://localhost:" + getPort() + "/" + body.shortCode();
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest redirectRequest = HttpRequest.newBuilder()
                .uri(URI.create(shortUrl))
                .GET()
                .build();
        HttpResponse<Void> redirectResponse = client.send(redirectRequest, HttpResponse.BodyHandlers.discarding());

        assertThat(redirectResponse.statusCode()).isEqualTo(302);
        assertThat(redirectResponse.headers().firstValue("Location"))
                .hasValue("https://example.com/integration");
    }

    @Test
    void delete_then_redirect_returns_404() throws Exception {
        // 생성
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/to-be-deleted");
        ResponseEntity<CreateUrlResponse> createResponse = restTemplate.postForEntity(
                "/api/urls", request, CreateUrlResponse.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String shortCode = createResponse.getBody().shortCode();

        // 삭제 → 204
        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/urls/" + shortCode, org.springframework.http.HttpMethod.DELETE, null, Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(repository.findByShortCode(shortCode)).isEmpty();

        // 삭제 후 리다이렉트 → 404 (캐시 즉시 무효화 확인)
        String shortUrl = "http://localhost:" + getPort() + "/" + shortCode;
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpResponse<Void> redirectResponse = client.send(
                HttpRequest.newBuilder().uri(URI.create(shortUrl)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(redirectResponse.statusCode()).isEqualTo(404);
    }

    @Test
    void delete_unknown_shortcode_returns_404() {
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/urls/missingX", org.springframework.http.HttpMethod.DELETE, null, Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void get_unknown_shortcode_returns_404() {
        ResponseEntity<Void> response = restTemplate.getForEntity("/abcdefg", Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void get_expired_shortcode_returns_410() {
        UrlMapping expired = UrlMapping.builder()
                .id(999999999999L)
                .shortCode("expiredX")
                .originalUrl("https://example.com/expired")
                .expiresAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .build();
        repository.save(expired);

        ResponseEntity<Void> response = restTemplate.getForEntity("/expiredX", Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    private int getPort() {
        // TestRestTemplate의 rootUri에서 포트를 추출
        String rootUri = restTemplate.getRootUri();
        // rootUri 형식: http://localhost:PORT
        return URI.create(rootUri).getPort();
    }
}
