package com.example.shortlink.cache;

import java.time.Instant;

public record RedirectCacheEntry(String originalUrl, Instant expiresAt) {
}
