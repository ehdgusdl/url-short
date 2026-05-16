package com.example.urlshort.controller;

import com.example.urlshort.domain.UrlMapping;
import com.example.urlshort.service.UrlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RedirectController.class)
class RedirectControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UrlService urlService;

    @Test
    void redirect_returns_302_with_location_when_found() throws Exception {
        UrlMapping mapping = UrlMapping.builder()
                .id(1L)
                .shortCode("aB3xK9p")
                .originalUrl("https://example.com/long")
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .build();
        when(urlService.find("aB3xK9p")).thenReturn(Optional.of(mapping));

        mockMvc.perform(get("/aB3xK9p"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/long"));
    }

    @Test
    void redirect_returns_404_when_not_found() throws Exception {
        when(urlService.find("nopenope")).thenReturn(Optional.empty());

        mockMvc.perform(get("/nopenope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void redirect_returns_410_when_expired() throws Exception {
        UrlMapping expired = UrlMapping.builder()
                .id(2L)
                .shortCode("expiredX")
                .originalUrl("https://example.com/old")
                .expiresAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .build();
        when(urlService.find("expiredX")).thenReturn(Optional.of(expired));

        mockMvc.perform(get("/expiredX"))
                .andExpect(status().isGone());
    }

    @Test
    void redirect_returns_404_when_shortcode_pattern_unmatched() throws Exception {
        mockMvc.perform(get("/abc"))
                .andExpect(status().isNotFound());
    }
}
