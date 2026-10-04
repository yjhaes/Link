package com.example.shortlink.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationStyle;

/** Fixed request groups with positive, bounded settings checked before serving requests. */
@ConfigurationProperties("short-link.rate-limit")
public final class RateLimitProperties {
    private final int createCapacity, redirectCapacity, managementWriteCapacity, managementQueryCapacity;
    private final String createRefillInterval;
    private final long refillMillis, redirectRefillMillis, managementWriteRefillMillis, managementQueryRefillMillis;

    public RateLimitProperties(int createCapacity, String createRefillInterval) {
        this(createCapacity, createRefillInterval, 60, "100ms", 5, "1s", 5, "1s");
    }

    @ConstructorBinding
    public RateLimitProperties(
            @DefaultValue("3") int createCapacity, @DefaultValue("6s") String createRefillInterval,
            @DefaultValue("60") int redirectCapacity, @DefaultValue("100ms") String redirectRefillInterval,
            @DefaultValue("5") int managementWriteCapacity, @DefaultValue("1s") String managementWriteRefillInterval,
            @DefaultValue("5") int managementQueryCapacity, @DefaultValue("1s") String managementQueryRefillInterval) {
        refillMillis = validate(createCapacity, createRefillInterval, "create");
        redirectRefillMillis = validate(redirectCapacity, redirectRefillInterval, "redirect");
        managementWriteRefillMillis = validate(managementWriteCapacity, managementWriteRefillInterval, "management-write");
        managementQueryRefillMillis = validate(managementQueryCapacity, managementQueryRefillInterval, "management-query");
        this.createCapacity = createCapacity;
        this.createRefillInterval = createRefillInterval;
        this.redirectCapacity = redirectCapacity;
        this.managementWriteCapacity = managementWriteCapacity;
        this.managementQueryCapacity = managementQueryCapacity;
    }
    private static long validate(int capacity, String interval, String group) {
        long millis;
        try { millis = DurationStyle.detectAndParse(interval).toMillis(); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("Invalid " + group + " refill interval."); }
        if (capacity < 1 || capacity > 1_000_000 || millis < 1 || millis > 86_400_000L
                || millis * capacity > 604_800_000L) {
            throw new IllegalArgumentException(group + " bucket must be positive and full refill at most seven days.");
        }
        return millis;
    }
    public int createCapacity() { return createCapacity; }
    public String createRefillInterval() { return createRefillInterval; }
    public long refillMillis() { return refillMillis; }
    public int redirectCapacity() { return redirectCapacity; }
    public long redirectRefillMillis() { return redirectRefillMillis; }
    public int managementWriteCapacity() { return managementWriteCapacity; }
    public long managementWriteRefillMillis() { return managementWriteRefillMillis; }
    public int managementQueryCapacity() { return managementQueryCapacity; }
    public long managementQueryRefillMillis() { return managementQueryRefillMillis; }
}
