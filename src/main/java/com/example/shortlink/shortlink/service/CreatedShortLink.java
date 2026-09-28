package com.example.shortlink.shortlink.service;

import java.time.Instant;

public record CreatedShortLink(String shortCode, Instant expiresAt) {
}
