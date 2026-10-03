package com.example.shortlink.service;

import java.time.Instant;

public record RedirectDecision(String originalUrl, Instant decidedAt) {}
