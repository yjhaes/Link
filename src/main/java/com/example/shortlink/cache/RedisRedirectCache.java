package com.example.shortlink.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

@Component
public class RedisRedirectCache implements RedirectCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisRedirectCache.class);
    private static final String KEY_PREFIX = "shortlink:redirect:v2:";
    private static final int SCHEMA_VERSION = 2;
    private static final String INITIALIZED_MARKER = "N:";
    private static final String EXISTING_MARKER = "E:";

    private static final DefaultRedisScript<String> READ_OR_INITIALIZE = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if value and redis.call('PTTL', KEYS[1]) > 0 then return 'E:' .. value end
            if value then redis.call('DEL', KEYS[1]) end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return 'N:' .. ARGV[1]
            """, String.class);

    private static final DefaultRedisScript<Long> REPLACE_VERSION = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> STORE_IF_VERSION = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then return 0 end
            local ok, entry = pcall(cjson.decode, value)
            if not ok or entry.schemaVersion ~= 2 or entry.generation ~= ARGV[1] then return 0 end
            if redis.call('PTTL', KEYS[1]) < 1 then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> DELETE_IF_VERSION = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then return 0 end
            local ok, entry = pcall(cjson.decode, value)
            if not ok or entry.schemaVersion ~= 2 or entry.generation ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    private static final DefaultRedisScript<Long> DELETE_IF_VALUE_MATCHES = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final DoubleSupplier jitterSource;
    private final long ttlMillis;
    private final long notFoundTtlMillis;
    private final long expiredTtlMillis;
    private final long disabledTtlMillis;
    private boolean enabled = true;

    @Autowired
    public RedisRedirectCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${short-link.redirect-cache.ttl:5m}") Duration ttl,
            @Value("${short-link.redirect-cache.not-found-ttl:30s}") Duration notFoundTtl,
            @Value("${short-link.redirect-cache.expired-ttl:5m}") Duration expiredTtl,
            @Value("${short-link.redirect-cache.disabled-ttl:15s}") Duration disabledTtl,
            @Value("${short-link.redirect-cache.enabled:true}") boolean enabled) {
        this(redisTemplate, objectMapper, clock, ttl, notFoundTtl, expiredTtl, disabledTtl, () -> ThreadLocalRandom.current().nextDouble());
        this.enabled = enabled;
    }

    public RedisRedirectCache(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Clock clock, Duration ttl) {
        this(redisTemplate, objectMapper, clock, ttl, Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofSeconds(15), () -> ThreadLocalRandom.current().nextDouble());
    }

    RedisRedirectCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            Duration ttl,
            Duration notFoundTtl,
            DoubleSupplier jitterSource) {
        this(redisTemplate, objectMapper, clock, ttl, notFoundTtl, Duration.ofMinutes(5), jitterSource);
    }

    RedisRedirectCache(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Clock clock,
            Duration ttl, Duration notFoundTtl, Duration expiredTtl, DoubleSupplier jitterSource) {
        this(redisTemplate, objectMapper, clock, ttl, notFoundTtl, expiredTtl, Duration.ofSeconds(15), jitterSource);
    }

    RedisRedirectCache(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Clock clock,
            Duration ttl, Duration notFoundTtl, Duration expiredTtl, Duration disabledTtl,
            DoubleSupplier jitterSource) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.jitterSource = jitterSource;
        this.ttlMillis = ttl.toMillis();
        this.notFoundTtlMillis = notFoundTtl.toMillis();
        this.expiredTtlMillis = expiredTtl.toMillis();
        this.disabledTtlMillis = disabledTtl.toMillis();
        if (ttlMillis < 1 || notFoundTtlMillis < 1 || expiredTtlMillis < 1 || disabledTtlMillis < 1) {
            throw new IllegalArgumentException("Redirect cache TTL must be at least one millisecond.");
        }
    }

    @Override
    public RedirectCacheRead find(String shortCode) {
        requireEnabled();
        String key = keyFor(shortCode);
        for (int attempt = 0; attempt < 2; attempt++) {
            String generation = UUID.randomUUID().toString();
            String placeholder = serialize(generation, RedirectCacheRead.Status.PLACEHOLDER, null);
            String response = redisTemplate.execute(
                    READ_OR_INITIALIZE,
                    List.of(key),
                    placeholder,
                    Long.toString(ttlMillis));
            if (response == null || response.length() < 3) {
                throw new IllegalStateException("Redis did not return a redirect cache entry.");
            }

            boolean initialized = response.startsWith(INITIALIZED_MARKER);
            if (!initialized && !response.startsWith(EXISTING_MARKER)) {
                throw new IllegalStateException("Redis returned an unknown redirect cache response.");
            }
            String serializedValue = response.substring(2);
            try {
                RedirectCacheRead stored = parse(serializedValue);
                if (initialized) {
                    return RedirectCacheRead.miss(stored.generation());
                }
                return stored;
            } catch (JsonProcessingException | DateTimeParseException | IllegalArgumentException exception) {
                LOGGER.warn("Ignoring malformed redirect cache value for short code {}.", shortCode, exception);
                deleteMalformedValue(key, serializedValue, shortCode);
            }
        }
        throw new IllegalStateException("Could not initialize a valid redirect cache entry.");
    }

    @Override
    public boolean storeIfVersion(
            String shortCode,
            String generation,
            RedirectCacheRead.Status status,
            RedirectCacheEntry entry) {
        if (!enabled) {
            return false;
        }
        validateBusinessResult(generation, status, entry);
        long entryTtlMillis = ttlMillisFor(status, entry);
        if (entryTtlMillis < 1) {
            return false;
        }

        String serializedValue = serialize(generation, status, entry);
        Long stored = redisTemplate.execute(
                STORE_IF_VERSION,
                List.of(keyFor(shortCode)),
                generation,
                serializedValue,
                Long.toString(entryTtlMillis));
        return Long.valueOf(1).equals(stored);
    }

    @Override
    public String replaceVersion(String shortCode) {
        requireEnabled();
        String generation = UUID.randomUUID().toString();
        String placeholder = serialize(generation, RedirectCacheRead.Status.PLACEHOLDER, null);
        Long replaced = redisTemplate.execute(
                REPLACE_VERSION,
                List.of(keyFor(shortCode)),
                placeholder,
                Long.toString(ttlMillis));
        if (!Long.valueOf(1).equals(replaced)) {
            throw new IllegalStateException("Redis did not replace the redirect cache version.");
        }
        return generation;
    }

    @Override
    public boolean deleteIfVersion(String shortCode, String generation) {
        if (!enabled) {
            return false;
        }
        Long deleted = redisTemplate.execute(
                DELETE_IF_VERSION,
                List.of(keyFor(shortCode)),
                generation);
        return Long.valueOf(1).equals(deleted);
    }

    private long ttlMillisFor(RedirectCacheRead.Status status, RedirectCacheEntry entry) {
        long configuredTtlMillis = jitteredTtlMillis(
                switch (status) {
                    case NOT_FOUND -> notFoundTtlMillis;
                    case EXPIRED -> expiredTtlMillis;
                    case DISABLED -> disabledTtlMillis;
                    default -> ttlMillis;
                });
        if (status != RedirectCacheRead.Status.REDIRECT || entry.expiresAt() == null) {
            return configuredTtlMillis;
        }

        long remainingMillis = Duration.between(clock.instant(), entry.expiresAt()).toMillis();
        if (remainingMillis < 1) {
            return 0;
        }
        return Math.min(configuredTtlMillis, remainingMillis);
    }

    private long jitteredTtlMillis(long maximumTtlMillis) {
        double randomValue = jitterSource.getAsDouble();
        if (!Double.isFinite(randomValue) || randomValue < 0 || randomValue > 1) {
            throw new IllegalStateException("Redirect cache jitter must be between zero and one.");
        }
        long reductionMillis = (long) Math.floor(maximumTtlMillis * 0.1 * randomValue);
        return Math.max(1, maximumTtlMillis - reductionMillis);
    }

    private void validateBusinessResult(
            String generation,
            RedirectCacheRead.Status status,
            RedirectCacheEntry entry) {
        if (status == null
                || status == RedirectCacheRead.Status.MISS
                || status == RedirectCacheRead.Status.PLACEHOLDER) {
            throw new IllegalArgumentException("Only business results can replace a redirect cache placeholder.");
        }
        RedirectCacheRead.result(status, generation, entry);
    }

    private String serialize(String generation, RedirectCacheRead.Status status, RedirectCacheEntry entry) {
        ObjectNode cacheValue = objectMapper.createObjectNode();
        cacheValue.put("schemaVersion", SCHEMA_VERSION);
        cacheValue.put("generation", generation);
        cacheValue.put("status", status.name());
        if (entry == null || entry.originalUrl() == null) {
            cacheValue.putNull("originalUrl");
        } else {
            cacheValue.put("originalUrl", entry.originalUrl());
        }
        if (entry == null || entry.expiresAt() == null) {
            cacheValue.putNull("expiresAt");
        } else {
            cacheValue.put("expiresAt", entry.expiresAt().toString());
        }

        try {
            return objectMapper.writeValueAsString(cacheValue);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize redirect cache value.", exception);
        }
    }

    private RedirectCacheRead parse(String serializedValue) throws JsonProcessingException {
        JsonNode value = objectMapper.readTree(serializedValue);
        if (value == null
                || !value.isObject()
                || value.size() != 5
                || !value.has("schemaVersion")
                || !value.get("schemaVersion").isInt()
                || value.get("schemaVersion").intValue() != SCHEMA_VERSION
                || !value.has("generation")
                || !value.get("generation").isTextual()
                || !value.has("status")
                || !value.get("status").isTextual()
                || !value.has("originalUrl")
                || !(value.get("originalUrl").isNull() || value.get("originalUrl").isTextual())
                || !value.has("expiresAt")
                || !(value.get("expiresAt").isNull() || value.get("expiresAt").isTextual())) {
            throw new IllegalArgumentException("Redirect cache value has an invalid shape.");
        }

        String generation = value.get("generation").textValue();
        UUID.fromString(generation);
        RedirectCacheRead.Status status = RedirectCacheRead.Status.valueOf(value.get("status").textValue());
        if (status == RedirectCacheRead.Status.MISS) {
            throw new IllegalArgumentException("A cache miss cannot be stored as a cache value.");
        }

        String originalUrl = value.get("originalUrl").isNull() ? null : value.get("originalUrl").textValue();
        Instant expiresAt = value.get("expiresAt").isNull()
                ? null
                : Instant.parse(value.get("expiresAt").textValue());
        RedirectCacheEntry entry = originalUrl == null && expiresAt == null
                ? null
                : new RedirectCacheEntry(originalUrl, expiresAt);
        return new RedirectCacheRead(status, generation, entry);
    }

    private void deleteMalformedValue(String key, String serializedValue, String shortCode) {
        try {
            redisTemplate.execute(DELETE_IF_VALUE_MATCHES, List.of(key), serializedValue);
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not delete malformed redirect cache value for short code {}.", shortCode, exception);
        }
    }

    private String keyFor(String shortCode) {
        return KEY_PREFIX + shortCode;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("Redirect cache is disabled; coordination cannot be confirmed.");
        }
    }

}
