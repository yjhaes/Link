package com.example.shortlink.shortlink.service;

import com.example.shortlink.common.error.ShortCodeGenerationException;
import com.example.shortlink.shortlink.persistence.ShortLinkEntity;
import com.example.shortlink.shortlink.persistence.ShortLinkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortLinkServiceTest {

    private ShortLinkMapper shortLinkMapper;
    private ShortLinkService shortLinkService;
    private ShortCodeIdIssuer shortCodeIdIssuer;

    @BeforeEach
    void setUp() {
        shortLinkMapper = mock(ShortLinkMapper.class);
        shortCodeIdIssuer = mock(ShortCodeIdIssuer.class);
        shortLinkService = new ShortLinkService(
                shortLinkMapper,
                shortCodeIdIssuer,
                new PermutedShortCodeEncoder(),
                Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC));
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
