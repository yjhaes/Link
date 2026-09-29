package com.example.shortlink.shortlink.service;

import com.example.shortlink.common.error.LinkDisabledException;
import com.example.shortlink.common.error.LinkExpiredException;
import com.example.shortlink.common.error.LinkNotFoundException;
import com.example.shortlink.common.error.ShortCodeGenerationException;
import com.example.shortlink.shortlink.persistence.ShortLinkEntity;
import com.example.shortlink.shortlink.persistence.ShortLinkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortLinkServiceTest {

    private ShortLinkMapper shortLinkMapper;
    private ShortLinkService shortLinkService;
    private ShortCodeIdIssuer shortCodeIdIssuer;
    private RedirectCache redirectCache;

    @BeforeEach
    void setUp() {
        shortLinkMapper = mock(ShortLinkMapper.class);
        shortCodeIdIssuer = mock(ShortCodeIdIssuer.class);
        redirectCache = mock(RedirectCache.class);
        shortLinkService = new ShortLinkService(
                shortLinkMapper,
                shortCodeIdIssuer,
                new PermutedShortCodeEncoder(),
                Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC),
                redirectCache);
    }

    @Test
    void redirectCacheHitReturnsTheOriginalUrlWithoutReadingMySql() {
        when(redirectCache.findPermanent("Ab12")).thenReturn(Optional.of("https://cached.example/"));

        String originalUrl = shortLinkService.findOriginalUrl("Ab12");

        assertThat(originalUrl).isEqualTo("https://cached.example/");
        verify(shortLinkMapper, never()).selectById("Ab12");
    }

    @Test
    void permanentRedirectCacheMissReadsMySqlAndCachesAnEnabledMapping() {
        ShortLinkEntity entity = redirectableMapping("Ab12", "https://mysql.example/", null, true);
        when(shortLinkMapper.selectById("Ab12")).thenReturn(entity);
        when(redirectCache.findPermanent("Ab12")).thenReturn(Optional.empty());

        String originalUrl = shortLinkService.findOriginalUrl("Ab12");

        assertThat(originalUrl).isEqualTo("https://mysql.example/");
        verify(shortLinkMapper).selectById("Ab12");
        verify(redirectCache).storePermanent("Ab12", "https://mysql.example/");
    }

    @Test
    void expiringRedirectCacheMissUsesMySqlWithoutCachingTheMapping() {
        ShortLinkEntity entity = redirectableMapping(
                "Ab12", "https://timed.example/", LocalDateTime.parse("2026-09-28T12:10:00"), true);
        when(shortLinkMapper.selectById("Ab12")).thenReturn(entity);
        when(redirectCache.findPermanent("Ab12")).thenReturn(Optional.empty());

        String originalUrl = shortLinkService.findOriginalUrl("Ab12");

        assertThat(originalUrl).isEqualTo("https://timed.example/");
        verify(redirectCache, never()).storePermanent("Ab12", "https://timed.example/");
    }

    @Test
    void redirectCacheReadFailureFallsBackToMySql() {
        when(redirectCache.findPermanent("Ab12")).thenThrow(new IllegalStateException("Redis unavailable"));
        when(shortLinkMapper.selectById("Ab12"))
                .thenReturn(redirectableMapping("Ab12", "https://mysql.example/", null, true));

        String originalUrl = shortLinkService.findOriginalUrl("Ab12");

        assertThat(originalUrl).isEqualTo("https://mysql.example/");
        verify(shortLinkMapper).selectById("Ab12");
    }

    @Test
    void redirectCacheWriteFailureDoesNotFailAnOtherwiseValidRedirect() {
        when(redirectCache.findPermanent("Ab12")).thenReturn(Optional.empty());
        when(shortLinkMapper.selectById("Ab12"))
                .thenReturn(redirectableMapping("Ab12", "https://mysql.example/", null, true));
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis unavailable"))
                .when(redirectCache).storePermanent("Ab12", "https://mysql.example/");

        String originalUrl = shortLinkService.findOriginalUrl("Ab12");

        assertThat(originalUrl).isEqualTo("https://mysql.example/");
    }

    @Test
    void invalidShortCodeDoesNotReadRedirectCache() {
        assertThatThrownBy(() -> shortLinkService.findOriginalUrl("bad"))
                .isInstanceOf(LinkNotFoundException.class);

        verify(redirectCache, never()).findPermanent("bad");
    }

    @Test
    void expiredAndDisabledMappingsAreNotCached() {
        when(redirectCache.findPermanent("Ab12")).thenReturn(Optional.empty());
        when(shortLinkMapper.selectById("Ab12"))
                .thenReturn(redirectableMapping(
                        "Ab12", "https://expired.example/", LocalDateTime.parse("2026-09-28T12:00:00"), true));

        assertThatThrownBy(() -> shortLinkService.findOriginalUrl("Ab12"))
                .isInstanceOf(LinkExpiredException.class);
        verify(redirectCache, never()).storePermanent("Ab12", "https://expired.example/");

        when(shortLinkMapper.selectById("Ab12"))
                .thenReturn(redirectableMapping("Ab12", "https://disabled.example/", null, false));
        assertThatThrownBy(() -> shortLinkService.findOriginalUrl("Ab12"))
                .isInstanceOf(LinkDisabledException.class);
        verify(redirectCache, never()).storePermanent("Ab12", "https://disabled.example/");
    }

    @Test
    void missingMappingIsNotCached() {
        when(redirectCache.findPermanent("Ab12")).thenReturn(Optional.empty());
        when(shortLinkMapper.selectById("Ab12")).thenReturn(null);

        assertThatThrownBy(() -> shortLinkService.findOriginalUrl("Ab12"))
                .isInstanceOf(LinkNotFoundException.class);

        verify(redirectCache, never()).storePermanent(anyString(), anyString());
    }

    private ShortLinkEntity redirectableMapping(
            String shortCode, String originalUrl, LocalDateTime expiresAt, boolean enabled) {
        ShortLinkEntity entity = new ShortLinkEntity();
        entity.setShortCode(shortCode);
        entity.setOriginalUrl(originalUrl);
        entity.setExpiresAt(expiresAt);
        entity.setEnabled(enabled);
        return entity;
    }

    @Test
    void retriesAPrimaryKeyCollisionAndReturnsTheSuccessfulCandidate() {
        when(shortCodeIdIssuer.issue()).thenReturn(1L, 2L);
        when(shortLinkMapper.insert(any(ShortLinkEntity.class)))
                .thenThrow(primaryKeyCollision())
                .thenReturn(1);

        CreatedShortLink createdLink = shortLinkService.create("https://example.com/path", null);

        assertThat(createdLink.shortCode()).isEqualTo("1ZXuEkNa");
        verify(shortCodeIdIssuer, times(2)).issue();
        verify(shortLinkMapper, times(2)).insert(any(ShortLinkEntity.class));
    }

    @Test
    void stopsAfterTwoPrimaryKeyCollisions() {
        when(shortCodeIdIssuer.issue()).thenReturn(1L, 2L);
        when(shortLinkMapper.insert(any(ShortLinkEntity.class))).thenThrow(primaryKeyCollision());

        assertThatThrownBy(() -> shortLinkService.create("https://example.com/path", null))
                .isInstanceOf(ShortCodeGenerationException.class);

        verify(shortCodeIdIssuer, times(2)).issue();
        verify(shortLinkMapper, times(2)).insert(any(ShortLinkEntity.class));
    }

    @Test
    void doesNotRetryADifferentUniqueConstraintCollision() {
        when(shortCodeIdIssuer.issue()).thenReturn(1L);
        SQLException sqlException = new SQLException(
                "Duplicate entry 'https://example.com/path' for key 'uq_original_url'",
                "23000",
                1062);
        when(shortLinkMapper.insert(any(ShortLinkEntity.class)))
                .thenThrow(new DuplicateKeyException(sqlException.getMessage(), sqlException));

        assertThatThrownBy(() -> shortLinkService.create("https://example.com/path", null))
                .isInstanceOf(DuplicateKeyException.class);

        verify(shortCodeIdIssuer, times(1)).issue();
        verify(shortLinkMapper, times(1)).insert(any(ShortLinkEntity.class));
    }

    @Test
    void anUnencodableIssuedIdFailsWithoutAttemptingToCreateAMapping() {
        when(shortCodeIdIssuer.issue()).thenReturn(0L);

        assertThatThrownBy(() -> shortLinkService.create("https://example.com/path", null))
                .isInstanceOf(ShortCodeGenerationException.class);

        verify(shortCodeIdIssuer, times(1)).issue();
        verify(shortLinkMapper, never()).insert(any(ShortLinkEntity.class));
    }

    private DuplicateKeyException primaryKeyCollision() {
        SQLException sqlException = new SQLException(
                "Duplicate entry 'aaaa0001' for key 'short_link.PRIMARY'",
                "23000",
                1062);
        return new DuplicateKeyException(sqlException.getMessage(), sqlException);
    }

}
