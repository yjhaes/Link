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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import org.springframework.data.redis.core.script.RedisScript;

class RedisRedirectCacheTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    @Test
    void notFoundTtlUsesItsOwnConfiguredMaximumAndBothJitterEndpoints() {
        assertNotFoundTtl(Duration.ofSeconds(30), 0, 30_000L);
        assertNotFoundTtl(Duration.ofSeconds(30), 1, 27_000L);
        assertNotFoundTtl(Duration.ofSeconds(10), 1, 9_000L);
    }

    private void assertNotFoundTtl(Duration maximum, double randomValue, long expectedMillis) {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AtomicLong actualMillis = new AtomicLong();
        doAnswer(invocation -> {
            actualMillis.set(Long.parseLong(invocation.getArgument(4)));
            return 1L;
        }).when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString());
        RedisRedirectCache cache = new RedisRedirectCache(redisTemplate, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5), maximum, () -> randomValue);

        assertThat(cache.storeIfVersion("Nope", "00000000-0000-0000-0000-000000000001",
                RedirectCacheRead.Status.NOT_FOUND, null)).isTrue();
        assertThat(actualMillis.get()).isEqualTo(expectedMillis);
    }

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
