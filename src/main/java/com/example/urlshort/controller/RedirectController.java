package com.example.urlshort.controller;

import com.example.urlshort.domain.UrlMapping;
import com.example.urlshort.service.UrlService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

@RestController
public class RedirectController {

    private final UrlService urlService;

    public RedirectController(UrlService urlService) {
        this.urlService = urlService;
    }

    @GetMapping("/{shortCode:[A-Za-z0-9]{6,10}}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        Optional<UrlMapping> mapping = urlService.find(shortCode);
        if (mapping.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (mapping.get().getExpiresAt().isBefore(Instant.now())) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(mapping.get().getOriginalUrl()))
                .build();
    }
}
