package com.example.shortlink.service;

import java.time.Instant;

public record RedirectCacheEntry(String originalUrl, Instant expiresAt) {
}
