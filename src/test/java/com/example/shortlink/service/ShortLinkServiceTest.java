package com.example.shortlink.service;

import com.example.shortlink.service.error.LinkDisabledException;
import com.example.shortlink.service.error.LinkExpiredException;
import com.example.shortlink.service.error.LinkNotFoundException;
import com.example.shortlink.service.error.ShortCodeGenerationException;
import com.example.shortlink.service.RedirectCacheRead;
import com.example.shortlink.persistence.ShortLinkEntity;
import com.example.shortlink.persistence.ShortLinkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

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

    private static final Instant BASE_TIME = Instant.parse("2026-09-28T12:00:00Z");
    private static final String GENERATION = "00000000-0000-0000-0000-000000000001";

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
                Clock.fixed(BASE_TIME, ZoneOffset.UTC),
                redirectCache);
    }

    @Test
    void disabledHitChecksExpiryWithoutReadingMySqlOrRenewingBeforeExpiry() {
        for (Instant expiresAt : new Instant[]{null, BASE_TIME.plusNanos(1), BASE_TIME, BASE_TIME.minusNanos(1)}) {
            org.mockito.Mockito.reset(redirectCache);
            when(redirectCache.find("Ab12")).thenReturn(RedirectCacheRead.result(
                    RedirectCacheRead.Status.DISABLED, GENERATION, new RedirectCacheEntry(null, expiresAt)));
            boolean expired = expiresAt != null && !BASE_TIME.isBefore(expiresAt);
            assertThatThrownBy(() -> shortLinkService.findOriginalUrl("Ab12"))
                    .isInstanceOf(expired ? LinkExpiredException.class : LinkDisabledException.class);
            if (expired) {
                verify(redirectCache).storeIfVersion("Ab12", GENERATION, RedirectCacheRead.Status.EXPIRED, null);
            }
            verify(redirectCache).find("Ab12");
            org.mockito.Mockito.verifyNoMoreInteractions(redirectCache);
        }
        org.mockito.Mockito.verifyNoInteractions(shortLinkMapper);
    }

    @Test
    void disabledMissCachesExpirySnapshotAndCacheFailureStillReturnsForbidden() {
        when(redirectCache.find("Ab12")).thenReturn(RedirectCacheRead.miss(GENERATION));
        when(shortLinkMapper.selectById("Ab12")).thenReturn(redirectableMapping(
                "Ab12", "https://example.com/", LocalDateTime.ofInstant(BASE_TIME.plusSeconds(1), ZoneOffset.UTC), false));
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis unavailable")).when(redirectCache)
                .storeIfVersion("Ab12", GENERATION, RedirectCacheRead.Status.DISABLED,
                        new RedirectCacheEntry(null, BASE_TIME.plusSeconds(1)));
        assertThatThrownBy(() -> shortLinkService.findOriginalUrl("Ab12")).isInstanceOf(LinkDisabledException.class);
        verify(redirectCache).storeIfVersion("Ab12", GENERATION, RedirectCacheRead.Status.DISABLED,
                new RedirectCacheEntry(null, BASE_TIME.plusSeconds(1)));
    }
    @Test
    void expiredResultHitDoesNotReadMySqlOrRenewTtl() {
        when(redirectCache.find("Ab12"))
                .thenReturn(RedirectCacheRead.result(RedirectCacheRead.Status.EXPIRED, GENERATION));
        assertThatThrownBy(() -> shortLinkService.findOriginalUrl("Ab12"))
                .isInstanceOf(LinkExpiredException.class);
        org.mockito.Mockito.verifyNoInteractions(shortLinkMapper);
        verify(redirectCache).find("Ab12");
        org.mockito.Mockito.verifyNoMoreInteractions(redirectCache);
    }

    @Test
    void redirectCacheHitReturnsTheOriginalUrlWithoutReadingMySql() {
        when(redirectCache.find("Ab12"))
                .thenReturn(RedirectCacheRead.redirect(
                        GENERATION, new RedirectCacheEntry("https://cached.example/", null)));

        String originalUrl = shortLinkService.findOriginalUrl("Ab12");

        assertThat(originalUrl).isEqualTo("https://cached.example/");
        verify(shortLinkMapper, never()).selectById("Ab12");
    }

    @Test
    void expiringRedirectCacheHitReturnsTheOriginalUrlBeforeExpiryWithoutReadingMySql() {
        when(redirectCache.find("Ab12")).thenReturn(RedirectCacheRead.redirect(
                GENERATION, new RedirectCacheEntry("https://cached.example/", BASE_TIME.plusMillis(1))));

        String originalUrl = shortLinkService.findOriginalUrl("Ab12");

        assertThat(originalUrl).isEqualTo("https://cached.example/");
        verify(shortLinkMapper, never()).selectById("Ab12");
        verify(redirectCache, never()).storeIfVersion("Ab12", GENERATION, RedirectCacheRead.Status.EXPIRED, null);
    }

    @Test
    void expiringRedirectCacheHitReturnsExpiredAtAndAfterTheExpiryInstant() {
        Instant expiresAt = BASE_TIME.plusSeconds(10);

        assertExpiredCacheHit(expiresAt, expiresAt);
        assertExpiredCacheHit(expiresAt.plusNanos(1), expiresAt);
    }

    @Test
    void expiredAndDisabledMappingReturnsExpiredBeforeDisabled() {
        when(redirectCache.find("Ab12")).thenReturn(RedirectCacheRead.miss(GENERATION));
        when(shortLinkMapper.selectById("Ab12")).thenReturn(redirectableMapping(
                "Ab12", "https://expired.example/", LocalDateTime.ofInstant(BASE_TIME, ZoneOffset.UTC), false));

        assertThatThrownBy(() -> shortLinkService.findOriginalUrl("Ab12"))
                .isInstanceOf(LinkExpiredException.class);
        verify(redirectCache).storeIfVersion("Ab12", GENERATION, RedirectCacheRead.Status.EXPIRED, null);
        verify(redirectCache, never()).storeIfVersion(
                "Ab12", GENERATION, RedirectCacheRead.Status.REDIRECT,
                new RedirectCacheEntry("https://expired.example/", BASE_TIME));
    }

    @Test
    void permanentRedirectCacheMissReadsMySqlAndCachesAnEnabledMapping() {
        ShortLinkEntity entity = redirectableMapping("Ab12", "https://mysql.example/", null, true);
        when(shortLinkMapper.selectById("Ab12")).thenReturn(entity);
        when(redirectCache.find("Ab12")).thenReturn(RedirectCacheRead.miss(GENERATION));

        String originalUrl = shortLinkService.findOriginalUrl("Ab12");

        assertThat(originalUrl).isEqualTo("https://mysql.example/");
        verify(shortLinkMapper).selectById("Ab12");
        verify(redirectCache).storeIfVersion("Ab12", GENERATION, RedirectCacheRead.Status.REDIRECT,
                new RedirectCacheEntry("https://mysql.example/", null));
    }

    @Test
    void redirectCachePlaceholderLoadsMySqlInsteadOfReturningNotFound() {
        when(redirectCache.find("Ab12")).thenReturn(RedirectCacheRead.placeholder(GENERATION));
        when(shortLinkMapper.selectById("Ab12"))
                .thenReturn(redirectableMapping("Ab12", "https://mysql.example/", null, true));

        String originalUrl = shortLinkService.findOriginalUrl("Ab12");

        assertThat(originalUrl).isEqualTo("https://mysql.example/");
        verify(shortLinkMapper).selectById("Ab12");
        verify(redirectCache).storeIfVersion("Ab12", GENERATION, RedirectCacheRead.Status.REDIRECT,
                new RedirectCacheEntry("https://mysql.example/", null));
    }

    @Test
    void expiringRedirectCacheMissReadsMySqlAndCachesTheExpiryInstant() {
        LocalDateTime expiresAt = LocalDateTime.parse("2026-09-28T12:10:00");
        ShortLinkEntity entity = redirectableMapping("Ab12", "https://timed.example/", expiresAt, true);
        when(shortLinkMapper.selectById("Ab12")).thenReturn(entity);
        when(redirectCache.find("Ab12")).thenReturn(RedirectCacheRead.miss(GENERATION));

        String originalUrl = shortLinkService.findOriginalUrl("Ab12");

        assertThat(originalUrl).isEqualTo("https://timed.example/");
        verify(redirectCache).storeIfVersion("Ab12", GENERATION, RedirectCacheRead.Status.REDIRECT,
                new RedirectCacheEntry(
                "https://timed.example/", expiresAt.toInstant(ZoneOffset.UTC)));
    }

    @Test
    void redirectCacheReadFailureFallsBackToMySql() {
        when(redirectCache.find("Ab12")).thenThrow(new IllegalStateException("Redis unavailable"));
        when(shortLinkMapper.selectById("Ab12"))
                .thenReturn(redirectableMapping("Ab12", "https://mysql.example/", null, true));

        String originalUrl = shortLinkService.findOriginalUrl("Ab12");

        assertThat(originalUrl).isEqualTo("https://mysql.example/");
        verify(shortLinkMapper).selectById("Ab12");
        verify(redirectCache, never()).storeIfVersion(
                anyString(), anyString(), any(RedirectCacheRead.Status.class), any());
    }

    @Test
    void redirectCacheWriteFailureDoesNotFailAnOtherwiseValidRedirect() {
        when(redirectCache.find("Ab12")).thenReturn(RedirectCacheRead.miss(GENERATION));
        when(shortLinkMapper.selectById("Ab12"))
                .thenReturn(redirectableMapping("Ab12", "https://mysql.example/", null, true));
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis unavailable"))
                .when(redirectCache).storeIfVersion("Ab12", GENERATION, RedirectCacheRead.Status.REDIRECT,
                        new RedirectCacheEntry("https://mysql.example/", null));

        String originalUrl = shortLinkService.findOriginalUrl("Ab12");

        assertThat(originalUrl).isEqualTo("https://mysql.example/");
    }

    @Test
    void invalidShortCodeDoesNotReadRedirectCache() {
        assertThatThrownBy(() -> shortLinkService.findOriginalUrl("bad"))
                .isInstanceOf(LinkNotFoundException.class);

        verify(redirectCache, never()).find("bad");
    }

    @Test
    void expiredAndDisabledMappingsAreNeverCachedAsRedirects() {
        when(redirectCache.find("Ab12")).thenReturn(RedirectCacheRead.miss(GENERATION));
        when(shortLinkMapper.selectById("Ab12"))
                .thenReturn(redirectableMapping(
                        "Ab12", "https://expired.example/", LocalDateTime.parse("2026-09-28T12:00:00"), true));

        assertThatThrownBy(() -> shortLinkService.findOriginalUrl("Ab12"))
                .isInstanceOf(LinkExpiredException.class);
        verify(redirectCache, never()).storeIfVersion("Ab12", GENERATION, RedirectCacheRead.Status.REDIRECT,
                new RedirectCacheEntry("https://expired.example/", BASE_TIME));

        when(shortLinkMapper.selectById("Ab12"))
                .thenReturn(redirectableMapping("Ab12", "https://disabled.example/", null, false));
        assertThatThrownBy(() -> shortLinkService.findOriginalUrl("Ab12"))
                .isInstanceOf(LinkDisabledException.class);
        verify(redirectCache, never()).storeIfVersion("Ab12", GENERATION, RedirectCacheRead.Status.REDIRECT,
                new RedirectCacheEntry("https://disabled.example/", null));
    }

    @Test
    void missingMappingCachesNotFoundWithTheReadVersion() {
        when(redirectCache.find("Ab12")).thenReturn(RedirectCacheRead.miss(GENERATION));
        when(shortLinkMapper.selectById("Ab12")).thenReturn(null);

        assertThatThrownBy(() -> shortLinkService.findOriginalUrl("Ab12"))
                .isInstanceOf(LinkNotFoundException.class);

        verify(redirectCache).storeIfVersion("Ab12", GENERATION, RedirectCacheRead.Status.NOT_FOUND, null);
    }

    @Test
    void notFoundCacheHitDoesNotReadMySqlOrRenewTheResult() {
        when(redirectCache.find("Ab12"))
                .thenReturn(RedirectCacheRead.result(RedirectCacheRead.Status.NOT_FOUND, GENERATION));

        assertThatThrownBy(() -> shortLinkService.findOriginalUrl("Ab12"))
                .isInstanceOf(LinkNotFoundException.class);
        verify(shortLinkMapper, never()).selectById("Ab12");
        verify(redirectCache).find("Ab12");
        org.mockito.Mockito.verifyNoMoreInteractions(redirectCache);
    }

    @Test
    void expiredSnapshotReturnsGoneEvenWhenCachingFails() {
        Instant expiresAt = BASE_TIME;
        when(redirectCache.find("Ab12")).thenReturn(RedirectCacheRead.redirect(
                GENERATION, new RedirectCacheEntry("https://cached.example/", expiresAt)));
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis unavailable"))
                .when(redirectCache).storeIfVersion("Ab12", GENERATION, RedirectCacheRead.Status.EXPIRED, null);

        assertThatThrownBy(() -> shortLinkService.findOriginalUrl("Ab12"))
                .isInstanceOf(LinkExpiredException.class);
        verify(shortLinkMapper, never()).selectById("Ab12");
        verify(redirectCache).storeIfVersion("Ab12", GENERATION, RedirectCacheRead.Status.EXPIRED, null);
    }

    private void assertExpiredCacheHit(Instant now, Instant expiresAt) {
        ShortLinkMapper mapper = mock(ShortLinkMapper.class);
        RedirectCache cache = mock(RedirectCache.class);
        ShortLinkService service = new ShortLinkService(
                mapper,
                shortCodeIdIssuer,
                new PermutedShortCodeEncoder(),
                Clock.fixed(now, ZoneOffset.UTC),
                cache);
        when(cache.find("Ab12")).thenReturn(RedirectCacheRead.redirect(
                GENERATION, new RedirectCacheEntry("https://cached.example/", expiresAt)));

        assertThatThrownBy(() -> service.findOriginalUrl("Ab12"))
                .isInstanceOf(LinkExpiredException.class);
        verify(cache).storeIfVersion("Ab12", GENERATION, RedirectCacheRead.Status.EXPIRED, null);
        verify(mapper, never()).selectById("Ab12");
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
    void coordinationRecoveryDoesNotReissueInsertOrOverwriteDisabledState() {
        when(shortLinkMapper.selectById("Ab12"))
                .thenReturn(redirectableMapping("Ab12", "https://example.com/", null, false));
        when(redirectCache.replaceVersion("Ab12")).thenReturn(GENERATION);

        shortLinkService.recoverCacheCoordination("Ab12");
        shortLinkService.recoverCacheCoordination("Ab12");

        verify(redirectCache, times(2)).replaceVersion("Ab12");
        org.mockito.Mockito.verifyNoInteractions(shortCodeIdIssuer);
        verify(shortLinkMapper, times(2)).selectById("Ab12");
        org.mockito.Mockito.verifyNoMoreInteractions(shortLinkMapper);
        assertThatThrownBy(() -> shortLinkService.findOriginalUrl("Ab12"))
                .isInstanceOf(LinkDisabledException.class);
    }

    @Test
    void databaseInsertFailureIsNotReportedAsCommittedCreation() {
        when(shortCodeIdIssuer.issue()).thenReturn(1L);
        IllegalStateException databaseFailure = new IllegalStateException("Commit acknowledgement lost");
        when(shortLinkMapper.insert(any(ShortLinkEntity.class))).thenThrow(databaseFailure);

        assertThatThrownBy(() -> shortLinkService.create("https://example.com/", null))
                .isSameAs(databaseFailure);
        verify(redirectCache, never()).replaceVersion(anyString());
    }

    @Test
    void unconfirmedInsertCountIsNotReportedAsCommittedCreation() {
        when(shortCodeIdIssuer.issue()).thenReturn(1L);
        when(shortLinkMapper.insert(any(ShortLinkEntity.class))).thenReturn(0);

        assertThatThrownBy(() -> shortLinkService.create("https://example.com/", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MySQL did not confirm inserting the short-link mapping.");
        verify(redirectCache, never()).replaceVersion(anyString());
    }

    @Test
    void recoveryRejectsAnUnknownOrMalformedCodeWithoutCoordinating() {
        assertThatThrownBy(() -> shortLinkService.recoverCacheCoordination("Nope"))
                .isInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> shortLinkService.recoverCacheCoordination("bad"))
                .isInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> shortLinkService.recoverCacheCoordination(null))
                .isInstanceOf(LinkNotFoundException.class);
        org.mockito.Mockito.verifyNoInteractions(redirectCache, shortCodeIdIssuer);
        verify(shortLinkMapper).selectById("Nope");
        org.mockito.Mockito.verifyNoMoreInteractions(shortLinkMapper);
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
