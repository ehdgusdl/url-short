package com.example.urlshort.controller;

import com.example.urlshort.domain.UrlMapping;
import com.example.urlshort.dto.CreateUrlRequest;
import com.example.urlshort.dto.CreateUrlResponse;
import com.example.urlshort.service.UrlService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/urls")
public class UrlController {

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    private final UrlService urlService;

    public UrlController(UrlService urlService) {
        this.urlService = urlService;
    }

    @PostMapping
    public ResponseEntity<CreateUrlResponse> create(@Valid @RequestBody CreateUrlRequest request) {
        UrlMapping mapping = urlService.create(request.originalUrl());
        String shortUrl = baseUrl + "/" + mapping.getShortCode();
        CreateUrlResponse response = new CreateUrlResponse(
                mapping.getShortCode(),
                shortUrl,
                mapping.getOriginalUrl()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
