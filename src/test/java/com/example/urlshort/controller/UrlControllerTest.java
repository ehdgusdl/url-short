package com.example.urlshort.controller;

import com.example.urlshort.domain.UrlMapping;
import com.example.urlshort.service.UrlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UrlController.class)
@TestPropertySource(properties = "app.base-url=http://localhost:8080")
class UrlControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    UrlService urlService;

    @Test
    void create_returns_201_with_short_url() throws Exception {
        when(urlService.create(eq("https://example.com/long")))
                .thenReturn(UrlMapping.builder()
                        .id(1L)
                        .shortCode("aB3xK9p")
                        .originalUrl("https://example.com/long")
                        .expiresAt(java.time.Instant.now().plus(7, java.time.temporal.ChronoUnit.DAYS))
                        .build());

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("originalUrl", "https://example.com/long"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("aB3xK9p"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/aB3xK9p"))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/long"));
    }

    @Test
    void create_returns_400_when_url_blank() throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("originalUrl", ""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_returns_400_when_url_invalid() throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("originalUrl", "not-a-url"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_returns_400_when_body_missing() throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status != 400 && status != 415) {
                        throw new AssertionError("Expected 400 or 415 but was " + status);
                    }
                });
    }
}
