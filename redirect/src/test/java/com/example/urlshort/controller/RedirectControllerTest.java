package com.example.urlshort.controller;

import com.example.urlshort.dto.UrlView;
import com.example.urlshort.event.ClickEventPublisher;
import com.example.urlshort.service.RedirectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RedirectController.class)
class RedirectControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RedirectService redirectService;

    @MockitoBean
    ClickEventPublisher clickEvents;

    @Test
    void redirect_returns_302_with_location_when_found() throws Exception {
        UrlView mapping = new UrlView(
                "aB3xK9p",
                "https://example.com/long",
                Instant.now().plus(7, ChronoUnit.DAYS));
        when(redirectService.find("aB3xK9p")).thenReturn(Optional.of(mapping));

        mockMvc.perform(get("/aB3xK9p"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/long"));

        // 302를 돌려준 요청만 집계 대상이다.
        verify(clickEvents).publish("aB3xK9p");
    }

    @Test
    void redirect_returns_404_when_not_found() throws Exception {
        when(redirectService.find("nopenope")).thenReturn(Optional.empty());

        mockMvc.perform(get("/nopenope"))
                .andExpect(status().isNotFound());

        verify(clickEvents, never()).publish("nopenope");
    }

    @Test
    void redirect_returns_410_when_expired() throws Exception {
        UrlView expired = new UrlView(
                "expiredX",
                "https://example.com/old",
                Instant.now().minus(1, ChronoUnit.DAYS));
        when(redirectService.find("expiredX")).thenReturn(Optional.of(expired));

        mockMvc.perform(get("/expiredX"))
                .andExpect(status().isGone());
    }

    @Test
    void redirect_returns_404_when_shortcode_pattern_unmatched() throws Exception {
        mockMvc.perform(get("/abc"))
                .andExpect(status().isNotFound());
    }
}
