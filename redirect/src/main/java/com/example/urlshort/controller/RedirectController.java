package com.example.urlshort.controller;

import com.example.urlshort.dto.UrlView;
import com.example.urlshort.service.UrlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

@Tag(name = "Redirect", description = "단축 URL 리다이렉트 API")
@RestController
public class RedirectController {

    private final UrlService urlService;

    public RedirectController(UrlService urlService) {
        this.urlService = urlService;
    }

    @Operation(summary = "단축 URL 리다이렉트", description = "단축 코드를 원본 URL로 302 리다이렉트합니다. 코드가 존재하지 않으면 404, 만료된 코드는 410 Gone을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "원본 URL로 리다이렉트", content = @Content),
            @ApiResponse(responseCode = "404", description = "단축 코드가 존재하지 않음", content = @Content),
            @ApiResponse(responseCode = "410", description = "단축 코드가 만료됨", content = @Content)
    })
    @GetMapping("/{shortCode:[A-Za-z0-9]{6,10}}")
    public ResponseEntity<Void> redirect(@Parameter(description = "단축 코드 (Base62 6~10자)", example = "aB3xK9p") @PathVariable String shortCode) {
        Optional<UrlView> mapping = urlService.find(shortCode);
        if (mapping.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (mapping.get().expiresAt().isBefore(Instant.now())) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(mapping.get().originalUrl()))
                .build();
    }
}
