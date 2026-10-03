package com.example.shortlink.service;



import com.example.shortlink.cache.RedirectCache;
import com.example.shortlink.persistence.ShortLinkEntity;
import com.example.shortlink.persistence.ShortLinkMapper;
import com.example.shortlink.service.error.LinkNotFoundException;
import com.example.shortlink.service.error.LinkExpiredException;
import com.example.shortlink.service.error.LinkStateConflictException;
import com.example.shortlink.service.error.StateCacheCoordinationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShortLinkStateServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private ShortLinkMapper mapper;
    private RedirectCache cache;
    private PlatformTransactionManager transactions;
    private ShortLinkStateService service;
    private List<Long> waits;

    @BeforeEach
    void setUp() {
        mapper = mock(ShortLinkMapper.class);
        cache = mock(RedirectCache.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ShortLinkEntity mapping = new ShortLinkEntity();
        mapping.setShortCode("Ab12");
        mapping.setEnabled(true);
        when(mapper.selectForUpdate("Ab12")).thenReturn(mapping);
        when(mapper.updateEnabled("Ab12", false)).thenReturn(1);
        waits = new ArrayList<>();
        service = new ShortLinkStateService(mapper, cache, transactions, Clock.fixed(NOW, ZoneOffset.UTC), waits::add);
    }

    @Test
    void malformedCodesAreRejectedBeforeDatabaseAndCacheAccess() {
        for (String code : new String[]{null, "", "abc", "123456789", "Ab_1", " Ab12", "中文测试"}) {
            assertThatThrownBy(() -> service.setEnabled(code, false)).isInstanceOf(LinkNotFoundException.class);
        }
        verifyNoInteractions(mapper, cache, transactions);
    }

    @Test
    void immediateCoordinationSuccessDoesNotWaitAndOnlyRunsAfterCommit() {
        AtomicBoolean committed = new AtomicBoolean();
        doAnswer(invocation -> { committed.set(true); return null; }).when(transactions).commit(any());
        when(cache.replaceVersion("Ab12")).thenAnswer(invocation -> {
            assertThat(committed.get()).isTrue();
            return "confirmed";
        });
        service.setEnabled("Ab12", false);
        assertThat(waits).isEmpty();
        verify(cache).replaceVersion("Ab12");
        verify(mapper).updateEnabled("Ab12", false);
    }

    @Test
    void successOnTheThirdAttemptUsesOnlyTwoWaitsAndOneDatabaseUpdate() {
        when(cache.replaceVersion("Ab12")).thenThrow(new IllegalStateException("first"))
                .thenThrow(new IllegalStateException("second")).thenReturn("confirmed");
        service.setEnabled("Ab12", false);
        assertThat(waits).containsExactly(50L, 100L);
        verify(cache, times(3)).replaceVersion("Ab12");
        verify(mapper).updateEnabled("Ab12", false);
        verify(transactions).commit(any());
    }

    @Test
    void successOnTheSecondAttemptDoesNotPerformTheThirdAttempt() {
        when(cache.replaceVersion("Ab12")).thenThrow(new IllegalStateException("first"))
                .thenReturn("confirmed");
        service.setEnabled("Ab12", false);
        assertThat(waits).containsExactly(50L);
        verify(cache, times(2)).replaceVersion("Ab12");
        verify(mapper).updateEnabled("Ab12", false);
    }

    @Test
    void exhaustionReportsTheCommittedCodeAndStopsAfterThreeAttempts() {
        when(cache.replaceVersion("Ab12")).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.setEnabled("Ab12", false))
                .isInstanceOfSatisfying(StateCacheCoordinationException.class,
                        failure -> assertThat(failure.shortCode()).isEqualTo("Ab12"));
        assertThat(waits).containsExactly(50L, 100L);
        verify(cache, times(3)).replaceVersion("Ab12");
        verify(mapper).updateEnabled("Ab12", false);
        verify(transactions).commit(any());
        verify(transactions, never()).rollback(any());
    }

    @Test
    void rejectedDuplicateAndExpiredOperationsNeverWriteOrCoordinate() {
        assertThatThrownBy(() -> service.setEnabled("Ab12", true))
                .isInstanceOf(LinkStateConflictException.class);
        ShortLinkEntity expired = new ShortLinkEntity();
        expired.setEnabled(true);
        expired.setExpiresAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(mapper.selectForUpdate("Ab12")).thenReturn(expired);
        for (boolean target : new boolean[]{true, false}) {
            assertThatThrownBy(() -> service.setEnabled("Ab12", target)).isInstanceOf(LinkExpiredException.class);
        }
        verify(mapper, never()).updateEnabled(anyString(), anyBoolean());
        verifyNoInteractions(cache);
        assertThat(waits).isEmpty();
    }

    @Test
    void anUnknownMappingDoesNotWriteOrCoordinate() {
        assertThatThrownBy(() -> service.setEnabled("Nope", false)).isInstanceOf(LinkNotFoundException.class);
        verify(mapper, never()).updateEnabled(anyString(), anyBoolean());
        verifyNoInteractions(cache);
    }

    @Test
    void databaseUpdateFailureIsNotRetriedOrReportedAsPartialCompletion() {
        when(mapper.updateEnabled("Ab12", false)).thenThrow(new IllegalStateException("DB unavailable"));
        assertThatThrownBy(() -> service.setEnabled("Ab12", false))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessage("DB unavailable");
        verify(mapper).updateEnabled("Ab12", false);
        verifyNoInteractions(cache);
        assertThat(waits).isEmpty();
    }

    @Test
    void anUnconfirmedCommitCannotStartCoordination() {
        doThrow(new IllegalStateException("Commit unconfirmed")).when(transactions).commit(any());
        assertThatThrownBy(() -> service.setEnabled("Ab12", false))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessage("Commit unconfirmed");
        verifyNoInteractions(cache);
        assertThat(waits).isEmpty();
    }

    @Test
    void expiryDuringRetryDoesNotRevalidateTheAlreadyCommittedOperation() {
        AtomicBoolean expiredDuringWait = new AtomicBoolean();
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.instant()).thenAnswer(invocation -> expiredDuringWait.get() ? NOW.plusSeconds(2) : NOW);
        ShortLinkEntity mapping = new ShortLinkEntity();
        mapping.setEnabled(true);
        mapping.setExpiresAt(LocalDateTime.ofInstant(NOW.plusSeconds(1), ZoneOffset.UTC));
        when(mapper.selectForUpdate("Ab12")).thenReturn(mapping);
        service = new ShortLinkStateService(mapper, cache, transactions, advancingClock,
                millis -> expiredDuringWait.set(true));
        when(cache.replaceVersion("Ab12")).thenThrow(new IllegalStateException("temporary"))
                .thenReturn("confirmed");
        service.setEnabled("Ab12", false);
        assertThat(expiredDuringWait.get()).isTrue();
        verify(mapper).selectForUpdate("Ab12");
        verify(mapper).updateEnabled("Ab12", false);
        verify(cache, times(2)).replaceVersion("Ab12");
    }

    @Test
    void anInterruptedWaitPreservesInterruptionAndReportsPartialCompletionWithoutFurtherAttempts() {
        when(cache.replaceVersion("Ab12")).thenThrow(new IllegalStateException("temporary"));
        service = new ShortLinkStateService(mapper, cache, transactions, Clock.fixed(NOW, ZoneOffset.UTC),
                millis -> { throw new InterruptedException("cancelled"); });
        try {
            assertThatThrownBy(() -> service.setEnabled("Ab12", false))
                    .isInstanceOf(StateCacheCoordinationException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(cache).replaceVersion("Ab12");
            verify(mapper).updateEnabled("Ab12", false);
        } finally {
            Thread.interrupted();
        }
    }
}
