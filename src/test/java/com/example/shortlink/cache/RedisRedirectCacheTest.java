package com.example.shortlink.cache;

import com.example.shortlink.service.RedirectCacheEntry;
import com.example.shortlink.service.RedirectCacheRead;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RedisRedirectCacheTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    @Test
    void doesNotWriteWhenLessThanOneMillisecondRemains() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisRedirectCache cache = new RedisRedirectCache(
                redisTemplate,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5));

        cache.storeIfVersion(
                "Ab12",
                "00000000-0000-0000-0000-000000000001",
                RedirectCacheRead.Status.REDIRECT,
                new RedirectCacheEntry("https://example.com/", NOW.plusNanos(999_999)));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void doesNotWriteWhenTheMappingIsAlreadyExpired() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisRedirectCache cache = new RedisRedirectCache(
                redisTemplate,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5));

        cache.storeIfVersion(
                "Ab12",
                "00000000-0000-0000-0000-000000000001",
                RedirectCacheRead.Status.REDIRECT,
                new RedirectCacheEntry("https://example.com/", NOW.minusNanos(1)));

        verifyNoInteractions(redisTemplate);
    }
}
