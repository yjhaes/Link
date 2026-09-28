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
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortLinkServiceTest {

    private ShortLinkMapper shortLinkMapper;
    private ShortLinkService shortLinkService;
    private CandidateShortCodeGenerator shortCodeGenerator;

    @BeforeEach
    void setUp() {
        shortLinkMapper = mock(ShortLinkMapper.class);
        shortCodeGenerator = new CandidateShortCodeGenerator("aaaa0001", "bbbb0002", "cccc0003", "dddd0004");
        shortLinkService = new ShortLinkService(
                shortLinkMapper,
                shortCodeGenerator,
                Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void retriesAPrimaryKeyCollisionAndReturnsTheSuccessfulCandidate() {
        when(shortLinkMapper.insert(any(ShortLinkEntity.class)))
                .thenThrow(primaryKeyCollision())
                .thenReturn(1);

        CreatedShortLink createdLink = shortLinkService.create("https://example.com/path", null);

        assertThat(createdLink.shortCode()).isEqualTo("bbbb0002");
        verify(shortLinkMapper, times(2)).insert(any(ShortLinkEntity.class));
    }

    @Test
    void stopsAfterFourPrimaryKeyCollisions() {
        when(shortLinkMapper.insert(any(ShortLinkEntity.class))).thenThrow(primaryKeyCollision());

        assertThatThrownBy(() -> shortLinkService.create("https://example.com/path", null))
                .isInstanceOf(ShortCodeGenerationException.class);

        verify(shortLinkMapper, times(4)).insert(any(ShortLinkEntity.class));
    }

    @Test
    void doesNotRetryADifferentUniqueConstraintCollision() {
        SQLException sqlException = new SQLException(
                "Duplicate entry 'https://example.com/path' for key 'uq_original_url'",
                "23000",
                1062);
        when(shortLinkMapper.insert(any(ShortLinkEntity.class)))
                .thenThrow(new DuplicateKeyException(sqlException.getMessage(), sqlException));

        assertThatThrownBy(() -> shortLinkService.create("https://example.com/path", null))
                .isInstanceOf(DuplicateKeyException.class);

        verify(shortLinkMapper, times(1)).insert(any(ShortLinkEntity.class));
    }

    private DuplicateKeyException primaryKeyCollision() {
        SQLException sqlException = new SQLException(
                "Duplicate entry 'aaaa0001' for key 'short_link.PRIMARY'",
                "23000",
                1062);
        return new DuplicateKeyException(sqlException.getMessage(), sqlException);
    }

    static final class CandidateShortCodeGenerator extends ShortCodeGenerator {

        private final Deque<String> candidates;

        CandidateShortCodeGenerator(String... shortCodes) {
            candidates = new ArrayDeque<>(Arrays.asList(shortCodes));
        }

        @Override
        public String generate() {
            return candidates.removeFirst();
        }
    }
}
