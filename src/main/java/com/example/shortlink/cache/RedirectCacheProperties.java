package com.example.shortlink.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;

/** Existing cache settings, validated once during binding before any requests are handled. */
@ConfigurationProperties("short-link.redirect-cache")
public class RedirectCacheProperties {
    private final boolean enabled;
    private final Duration ttl;
    private final Duration notFoundTtl;
    private final Duration expiredTtl;
    private final Duration disabledTtl;
    private final Duration loadWait;

    // Bind duration text explicitly: an empty configured value must fail, rather than use a default.
    @ConstructorBinding
    public RedirectCacheProperties(@DefaultValue("true") boolean enabled,
            @DefaultValue("5m") String ttl, @DefaultValue("30s") String notFoundTtl,
            @DefaultValue("5m") String expiredTtl, @DefaultValue("15s") String disabledTtl,
            @DefaultValue("200ms") String loadWait) {
        this(enabled, DurationStyle.detectAndParse(ttl), DurationStyle.detectAndParse(notFoundTtl),
                DurationStyle.detectAndParse(expiredTtl), DurationStyle.detectAndParse(disabledTtl),
                DurationStyle.detectAndParse(loadWait));
    }

    public RedirectCacheProperties(boolean enabled, Duration ttl, Duration notFoundTtl,
            Duration expiredTtl, Duration disabledTtl, Duration loadWait) {
        requirePositive(ttl, "ttl", false);
        requirePositive(notFoundTtl, "not-found-ttl", false);
        requirePositive(expiredTtl, "expired-ttl", false);
        requirePositive(disabledTtl, "disabled-ttl", false);
        requirePositive(loadWait, "load-wait", true);
        this.enabled = enabled;
        this.ttl = ttl;
        this.notFoundTtl = notFoundTtl;
        this.expiredTtl = expiredTtl;
        this.disabledTtl = disabledTtl;
        this.loadWait = loadWait;
    }

    private static void requirePositive(Duration duration, String key, boolean nanos) {
        try {
            if (duration == null || duration.isNegative()
                    || (nanos ? duration.toNanos() : duration.toMillis()) < 1) {
                throw new IllegalArgumentException("short-link.redirect-cache." + key
                        + " must be positive and representable in " + (nanos ? "nanoseconds." : "milliseconds."));
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("short-link.redirect-cache." + key + " overflows its time unit.", exception);
        }
    }

    public boolean isEnabled() { return enabled; }
    public Duration getTtl() { return ttl; }
    public Duration getNotFoundTtl() { return notFoundTtl; }
    public Duration getExpiredTtl() { return expiredTtl; }
    public Duration getDisabledTtl() { return disabledTtl; }
    public Duration getLoadWait() { return loadWait; }
}
