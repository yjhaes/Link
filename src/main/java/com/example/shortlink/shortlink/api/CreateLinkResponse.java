package com.example.shortlink.shortlink.api;

import java.time.Instant;

public record CreateLinkResponse(
        String shortCode,
        String shortUrl,
        Instant expiresAt) {
}
