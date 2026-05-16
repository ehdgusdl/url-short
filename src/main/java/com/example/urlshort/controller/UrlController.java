package com.example.urlshort.controller;

import com.example.urlshort.domain.UrlMapping;
import com.example.urlshort.dto.CreateUrlRequest;
import com.example.urlshort.dto.CreateUrlResponse;
import com.example.urlshort.service.UrlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "URL", description = "단축 URL 생성 API")
@RestController
@RequestMapping("/api/urls")
public class UrlController {

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    private final UrlService urlService;

    public UrlController(UrlService urlService) {
        this.urlService = urlService;
    }

    @Operation(summary = "단축 URL 생성", description = "원본 URL을 받아 7자리 Base62 단축 코드를 발급합니다. 단축 코드는 7일간 유효하며, 만료 후에는 410 Gone이 반환됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "단축 URL 생성 성공"),
            @ApiResponse(responseCode = "400", description = "원본 URL이 비어 있거나 형식이 올바르지 않음", content = @Content)
    })
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
