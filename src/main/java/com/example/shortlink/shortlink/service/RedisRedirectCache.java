package com.example.shortlink.shortlink.service;

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
import java.time.Duration;
import java.util.Optional;

@Component
public class RedisRedirectCache implements RedirectCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisRedirectCache.class);
    private static final String KEY_PREFIX = "shortlink:redirect:v1:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long ttlMillis;

    public RedisRedirectCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${short-link.redirect-cache.ttl:5m}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttlMillis = ttl.toMillis();
        if (ttlMillis < 1) {
            throw new IllegalArgumentException("Redirect cache TTL must be at least one millisecond.");
        }
    }

    @Override
    public Optional<String> findPermanent(String shortCode) {
        String key = keyFor(shortCode);
        String serializedValue = redisTemplate.opsForValue().get(key);
        if (serializedValue == null) {
            return Optional.empty();
        }

        try {
            JsonNode value = objectMapper.readTree(serializedValue);
            if (!isPermanentCacheValue(value)) {
                LOGGER.warn("Ignoring malformed redirect cache value for short code {}.", shortCode);
                deleteMalformedValue(key, shortCode);
                return Optional.empty();
            }
            return Optional.of(value.get("originalUrl").textValue());
        } catch (JsonProcessingException exception) {
            LOGGER.warn("Could not parse redirect cache value for short code {}.", shortCode, exception);
            deleteMalformedValue(key, shortCode);
            return Optional.empty();
        }
    }

    @Override
    public void storePermanent(String shortCode, String originalUrl) {
        ObjectNode cacheValue = objectMapper.createObjectNode();
        cacheValue.put("originalUrl", originalUrl);
        cacheValue.putNull("expiresAt");

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
                        Expiration.milliseconds(ttlMillis),
                        RedisStringCommands.SetOption.UPSERT));
        if (!Boolean.TRUE.equals(stored)) {
            throw new IllegalStateException("Redis did not store the redirect cache value.");
        }
    }

    private boolean isPermanentCacheValue(JsonNode value) {
        return value != null
                && value.isObject()
                && value.size() == 2
                && value.has("originalUrl")
                && value.get("originalUrl").isTextual()
                && !value.get("originalUrl").textValue().isBlank()
                && value.has("expiresAt")
                && value.get("expiresAt").isNull();
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
