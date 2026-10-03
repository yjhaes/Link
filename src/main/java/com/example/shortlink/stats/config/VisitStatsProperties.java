package com.example.shortlink.stats.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Base64;

@ConfigurationProperties("short-link.stats")
public record VisitStatsProperties(
        boolean enabled,
        String visitorKey,
        Integer visitorKeyVersion,
        Long connectionTimeoutMs,
        Long validationTimeoutMs,
        Integer connectTimeoutMs,
        Integer socketTimeoutMs,
        Integer statementTimeoutSeconds,
        Integer lockTimeoutSeconds) {
    public VisitStatsProperties {
        connectionTimeoutMs = connectionTimeoutMs == null ? 1000L : connectionTimeoutMs;
        validationTimeoutMs = validationTimeoutMs == null ? 500L : validationTimeoutMs;
        connectTimeoutMs = connectTimeoutMs == null ? 500 : connectTimeoutMs;
        socketTimeoutMs = socketTimeoutMs == null ? 1000 : socketTimeoutMs;
        statementTimeoutSeconds = statementTimeoutSeconds == null ? 1 : statementTimeoutSeconds;
        lockTimeoutSeconds = lockTimeoutSeconds == null ? 1 : lockTimeoutSeconds;
        if (connectionTimeoutMs < 250
                || validationTimeoutMs < 250
                || validationTimeoutMs >= connectionTimeoutMs
                || connectTimeoutMs <= 0
                || socketTimeoutMs <= 0
                || statementTimeoutSeconds <= 0
                || lockTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("Invalid statistics timeout configuration.");
        }
        if (visitorKey != null && !visitorKey.isEmpty()) {
            try {
                if (Base64.getDecoder().decode(visitorKey).length < 32)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(
                        "Statistics visitor key must be Base64 with at least 32 bytes.");
            }
        }
        if (visitorKeyVersion != null && (visitorKeyVersion < 1 || visitorKeyVersion > 65535)) {
            throw new IllegalArgumentException("Statistics visitor key version must be 1..65535.");
        }
        if (enabled && (visitorKey == null || visitorKey.isEmpty() || visitorKeyVersion == null)) {
            throw new IllegalArgumentException(
                    "Enabled statistics requires a visitor key and version.");
        }
    }
}
