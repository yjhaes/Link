package com.example.shortlink.service;

import java.time.Instant;

public record CreatedShortLink(String shortCode, Instant expiresAt) {}
