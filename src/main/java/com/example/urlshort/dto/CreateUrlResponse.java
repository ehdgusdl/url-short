package com.example.urlshort.dto;

public record CreateUrlResponse(
        String shortCode,
        String shortUrl,
        String originalUrl
) {
}
