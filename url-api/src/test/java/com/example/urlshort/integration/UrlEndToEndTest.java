package com.example.urlshort.integration;

import com.example.urlshort.cache.UrlCacheWriter;
import com.example.urlshort.dto.CreateUrlRequest;
import com.example.urlshort.dto.CreateUrlResponse;
import com.example.urlshort.dto.UrlView;
import com.example.urlshort.repository.UrlMappingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * url-api 서비스 단독 E2E. 리다이렉트는 별도 서비스(redirect)로 분리됐으므로
 * 두 서비스를 잇는 흐름은 docker compose 기동 후 스크립트로 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UrlEndToEndTest extends AbstractMySqlContainerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UrlMappingRepository repository;

    @Autowired
    private UrlCacheWriter cache;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
        // 테스트 간 캐시(L2) 격리 — 잔존 캐시로 인한 Stale 결과 방지.
        cache.invalidateAll();
    }

    @Test
    void create_persists_and_returns_short_code() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/integration");

        ResponseEntity<CreateUrlResponse> response = restTemplate.postForEntity(
                "/api/urls", request, CreateUrlResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        CreateUrlResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.shortCode()).hasSize(7);
        assertThat(body.originalUrl()).isEqualTo("https://example.com/integration");
        assertThat(repository.findByShortCode(body.shortCode())).isPresent();
    }

    @Test
    void list_returns_created_urls() {
        restTemplate.postForEntity("/api/urls", new CreateUrlRequest("https://example.com/a"), CreateUrlResponse.class);
        restTemplate.postForEntity("/api/urls", new CreateUrlRequest("https://example.com/b"), CreateUrlResponse.class);

        ResponseEntity<List<UrlView>> response = restTemplate.exchange(
                "/api/urls", HttpMethod.GET, null, new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2)
                .extracting(UrlView::originalUrl)
                .containsExactlyInAnyOrder("https://example.com/a", "https://example.com/b");
    }

    @Test
    void delete_removes_row() {
        ResponseEntity<CreateUrlResponse> created = restTemplate.postForEntity(
                "/api/urls", new CreateUrlRequest("https://example.com/to-be-deleted"), CreateUrlResponse.class);
        String shortCode = created.getBody().shortCode();

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/urls/" + shortCode, HttpMethod.DELETE, null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(repository.findByShortCode(shortCode)).isEmpty();
    }

    @Test
    void delete_unknown_shortcode_returns_404() {
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/urls/missingX", HttpMethod.DELETE, null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
