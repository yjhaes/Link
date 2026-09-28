package com.example.shortlink.shortlink.service;

import java.time.LocalDateTime;

public record ShortLink(
        String shortCode,
        String originalUrl,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        boolean enabled) {
}
