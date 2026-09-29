package com.example.shortlink.cache;

import com.example.shortlink.service.RedirectCache;
import com.example.shortlink.service.RedirectCacheEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

@Component
public class RedisRedirectCache implements RedirectCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisRedirectCache.class);
    private static final String KEY_PREFIX = "shortlink:redirect:v1:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final long ttlMillis;

    public RedisRedirectCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${short-link.redirect-cache.ttl:5m}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.ttlMillis = ttl.toMillis();
        if (ttlMillis < 1) {
            throw new IllegalArgumentException("Redirect cache TTL must be at least one millisecond.");
        }
    }

    @Override
    public Optional<RedirectCacheEntry> find(String shortCode) {
        String key = keyFor(shortCode);
        String serializedValue = redisTemplate.opsForValue().get(key);
        if (serializedValue == null) {
            return Optional.empty();
        }

        try {
            JsonNode value = objectMapper.readTree(serializedValue);
            if (!isRedirectCacheValue(value)) {
                LOGGER.warn("Ignoring malformed redirect cache value for short code {}.", shortCode);
                deleteMalformedValue(key, shortCode);
                return Optional.empty();
            }
            Instant expiresAt = value.get("expiresAt").isNull()
                    ? null
                    : Instant.parse(value.get("expiresAt").textValue());
            return Optional.of(new RedirectCacheEntry(value.get("originalUrl").textValue(), expiresAt));
        } catch (JsonProcessingException | DateTimeParseException exception) {
            LOGGER.warn("Could not parse redirect cache value for short code {}.", shortCode, exception);
            deleteMalformedValue(key, shortCode);
            return Optional.empty();
        }
    }

    @Override
    public void store(String shortCode, RedirectCacheEntry entry) {
        String originalUrl = entry.originalUrl();
        Instant expiresAt = entry.expiresAt();
        long entryTtlMillis = ttlMillisFor(expiresAt);
        if (entryTtlMillis < 1) {
            return;
        }

        ObjectNode cacheValue = objectMapper.createObjectNode();
        cacheValue.put("originalUrl", originalUrl);
        if (expiresAt == null) {
            cacheValue.putNull("expiresAt");
        } else {
            cacheValue.put("expiresAt", expiresAt.toString());
        }

        String serializedValue;
        try {
            serializedValue = objectMapper.writeValueAsString(cacheValue);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize redirect cache value.", exception);
        }

        byte[] key = keyFor(shortCode).getBytes(StandardCharsets.UTF_8);
        byte[] value = serializedValue.getBytes(StandardCharsets.UTF_8);
        Boolean stored = redisTemplate.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().set(
                        key,
                        value,
                        Expiration.milliseconds(entryTtlMillis),
                        RedisStringCommands.SetOption.UPSERT));
        if (!Boolean.TRUE.equals(stored)) {
            throw new IllegalStateException("Redis did not store the redirect cache value.");
        }
    }

    @Override
    public void delete(String shortCode) {
        redisTemplate.delete(keyFor(shortCode));
    }

    private long ttlMillisFor(Instant expiresAt) {
        if (expiresAt == null) {
            return ttlMillis;
        }

        long remainingMillis = Duration.between(clock.instant(), expiresAt).toMillis();
        if (remainingMillis < 1) {
            return 0;
        }
        return Math.min(ttlMillis, remainingMillis);
    }

    private boolean isRedirectCacheValue(JsonNode value) {
        return value != null
                && value.isObject()
                && value.size() == 2
                && value.has("originalUrl")
                && value.get("originalUrl").isTextual()
                && !value.get("originalUrl").textValue().isBlank()
                && value.has("expiresAt")
                && (value.get("expiresAt").isNull() || value.get("expiresAt").isTextual());
    }

    private void deleteMalformedValue(String key, String shortCode) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not delete malformed redirect cache value for short code {}.", shortCode, exception);
        }
    }

    private String keyFor(String shortCode) {
        return KEY_PREFIX + shortCode;
    }
}
