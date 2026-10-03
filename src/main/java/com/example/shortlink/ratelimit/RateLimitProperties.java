package com.example.shortlink.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationStyle;

/** Positive, bounded settings are parsed once before any requests are accepted. */
@ConfigurationProperties("short-link.rate-limit")
public final class RateLimitProperties {
    private final int createCapacity;
    private final String createRefillInterval;
    private final long refillMillis;

    @ConstructorBinding
    public RateLimitProperties(
            @DefaultValue("3") int createCapacity,
            @DefaultValue("6s") String createRefillInterval) {
        long millis;
        try {
            millis = DurationStyle.detectAndParse(createRefillInterval).toMillis();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid create refill interval.");
        }
        if (createCapacity < 1
                || createCapacity > 1_000_000
                || millis < 1
                || millis > 86_400_000L
                || millis * createCapacity > 604_800_000L) {
            throw new IllegalArgumentException(
                    "Create bucket capacity/refill must be positive and full refill at most seven days.");
        }
        this.createCapacity = createCapacity;
        this.createRefillInterval = createRefillInterval;
        this.refillMillis = millis;
    }

    public int createCapacity() {
        return createCapacity;
    }

    public String createRefillInterval() {
        return createRefillInterval;
    }

    public long refillMillis() {
        return refillMillis;
    }
}
