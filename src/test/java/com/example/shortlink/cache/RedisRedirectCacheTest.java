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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void disabledCacheCannotReadRefillOrConfirmCoordination() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisRedirectCache cache = new RedisRedirectCache(redis, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5), Duration.ofSeconds(30),
                Duration.ofMinutes(5), Duration.ofSeconds(15), false);

        assertThatThrownBy(() -> cache.find("Ab12")).isInstanceOf(IllegalStateException.class);
        assertThat(cache.storeIfVersion("Ab12", "old-version", RedirectCacheRead.Status.NOT_FOUND, null)).isFalse();
        assertThat(cache.deleteIfVersion("Ab12", "old-version")).isFalse();
        assertThatThrownBy(() -> cache.replaceVersion("Ab12")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(redis);
    }

    @Test
    void disabledTtlUsesIndependentMaximumAndKeepsBusinessExpiry() {
        for (double random : new double[]{0, 1}) {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            AtomicLong ttl = new AtomicLong();
            doAnswer(invocation -> {
                ttl.set(Long.parseLong(invocation.getArgument(4)));
                return 1L;
            }).when(redis).execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString());
            RedisRedirectCache cache = new RedisRedirectCache(redis, new ObjectMapper(),
                    Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(1), Duration.ofSeconds(30),
                    Duration.ofMinutes(5), Duration.ofSeconds(15), () -> random);
            assertThat(cache.storeIfVersion("Dis1", "00000000-0000-0000-0000-000000000001",
                    RedirectCacheRead.Status.DISABLED, new RedirectCacheEntry(null, NOW.plusMillis(1)))).isTrue();
            assertThat(ttl.get()).isEqualTo(random == 0 ? 15_000L : 13_500L);
        }
    }
    @Test
    void notFoundTtlUsesItsOwnConfiguredMaximumAndBothJitterEndpoints() {
        assertNotFoundTtl(Duration.ofSeconds(30), 0, 30_000L);
        assertNotFoundTtl(Duration.ofSeconds(30), 1, 27_000L);
        assertNotFoundTtl(Duration.ofSeconds(10), 1, 9_000L);
    }

    private void assertNotFoundTtl(Duration maximum, double randomValue, long expectedMillis) {
        assertResultTtl(RedirectCacheRead.Status.NOT_FOUND, Duration.ofMinutes(5), maximum,
                Duration.ofMinutes(5), randomValue, expectedMillis);
    }

    @Test
    void expiredTtlIsIndependentOfPositiveTtlAndUsesBothJitterEndpoints() {
        assertExpiredTtl(Duration.ofMinutes(5), 0, 300_000L);
        assertExpiredTtl(Duration.ofMinutes(5), 1, 270_000L);
        assertExpiredTtl(Duration.ofSeconds(10), 1, 9_000L);
    }

    private void assertExpiredTtl(Duration maximum, double randomValue, long expectedMillis) {
        assertResultTtl(RedirectCacheRead.Status.EXPIRED, Duration.ofSeconds(1), Duration.ofSeconds(30),
                maximum, randomValue, expectedMillis);
    }

    private void assertResultTtl(RedirectCacheRead.Status status, Duration positiveMaximum,
                                 Duration notFoundMaximum, Duration expiredMaximum,
                                 double randomValue, long expectedMillis) {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AtomicLong actualMillis = new AtomicLong();
        doAnswer(invocation -> {
            actualMillis.set(Long.parseLong(invocation.getArgument(4)));
            return 1L;
        }).when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString());
        RedisRedirectCache cache = new RedisRedirectCache(redisTemplate, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), positiveMaximum, notFoundMaximum,
                expiredMaximum, () -> randomValue);
        assertThat(cache.storeIfVersion("Exp1", "00000000-0000-0000-0000-000000000001",
                status, null)).isTrue();
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
