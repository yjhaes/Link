package com.example.shortlink.shortlink.service;

import java.time.Instant;
import java.util.Optional;

public interface RedirectCache {

    Optional<RedirectCacheEntry> find(String shortCode);

    void store(String shortCode, String originalUrl, Instant expiresAt);

    void delete(String shortCode);
}
