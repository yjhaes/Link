package com.example.shortlink.service;

import com.example.shortlink.cache.RedirectCache;
import com.example.shortlink.cache.RedirectCacheEntry;
import com.example.shortlink.cache.RedirectCacheRead;
import com.example.shortlink.shortcode.PermutedShortCodeEncoder;
import com.example.shortlink.shortcode.ShortCodeIdIssuer;
import com.example.shortlink.persistence.ShortLinkEntity;
import com.example.shortlink.persistence.ShortLinkMapper;
import com.example.shortlink.service.error.LinkDisabledException;
import com.example.shortlink.service.error.LinkExpiredException;
import com.example.shortlink.service.error.LinkNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedirectLoadCoalescingTest {
    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    @ParameterizedTest
    @ValueSource(ints = {302, 404, 403, 410})
    void overlappingRequestsShareOneQueryForEveryBusinessResult(int status) throws Exception {
        try (Scenario scenario = new Scenario(Duration.ofSeconds(5))) {
            scenario.blockFirstQuery(mapping(status, null));
            Request first = scenario.start("Ab12");
            scenario.awaitQuery();
            Request second = scenario.start("Ab12");
            second.awaitWaiting();
            assertThat(scenario.queries.get()).isEqualTo(1);
            scenario.release.countDown();
            assertThat(first.result()).isEqualTo(status);
            assertThat(second.result()).isEqualTo(status);
            verify(scenario.cache).storeIfVersion(eq("Ab12"), eq("version-1"), any(), any());
            // Even if the cache stays a miss, the successful task must be removed.
            assertThat(scenario.status("Ab12")).isEqualTo(status);
            assertThat(scenario.queries.get()).isEqualTo(2);
        }
    }

    @Test
    void differentCodesCanQueryConcurrently() throws Exception {
        try (Scenario scenario = new Scenario(Duration.ofSeconds(5))) {
            CountDownLatch bothEntered = new CountDownLatch(2);
            when(scenario.mapper.selectById(anyString())).thenAnswer(call -> {
                bothEntered.countDown();
                assertThat(scenario.release.await(5, TimeUnit.SECONDS)).isTrue();
                return mapping(302, null);
            });
            Request first = scenario.start("Ab12");
            Request second = scenario.start("Cd34");
            assertThat(bothEntered.await(5, TimeUnit.SECONDS)).isTrue();
            scenario.release.countDown();
            assertThat(first.result()).isEqualTo(302);
            assertThat(second.result()).isEqualTo(302);
        }
    }

    @Test
    void timeoutRereadsCacheAndQueriesIndependentlyWithoutCancellingSharedLoad() throws Exception {
        try (Scenario scenario = new Scenario(Duration.ofMillis(100))) {
            scenario.blockFirstQuery(mapping(302, null));
            Request loader = scenario.start("Ab12");
            scenario.awaitQuery();
            Request timedOut = scenario.start("Ab12");
            assertThat(timedOut.result()).isEqualTo(302);
            assertThat(loader.task.isDone()).isFalse();
            assertThat(scenario.queries.get()).isEqualTo(2);
            verify(scenario.cache, times(4)).find("Ab12");
            Request patient = scenario.start("Ab12");
            patient.awaitWaiting();
            assertThat(scenario.queries.get()).isEqualTo(2);
            scenario.release.countDown();
            assertThat(patient.result()).isEqualTo(302);
            assertThat(loader.result()).isEqualTo(302);
        }
    }

    @Test
    void timeoutCacheHitAvoidsAnIndependentQuery() throws Exception {
        try (Scenario scenario = new Scenario(Duration.ofMillis(100))) {
            scenario.blockFirstQuery(mapping(302, null));
            Request loader = scenario.start("Ab12");
            scenario.awaitQuery();
            Request waiter = scenario.start("Ab12");
            waiter.awaitWaiting();
            when(scenario.cache.find("Ab12")).thenReturn(RedirectCacheRead.redirect(
                    "version-1", new RedirectCacheEntry("https://example.com/", null)));
            assertThat(waiter.result()).isEqualTo(302);
            assertThat(loader.task.isDone()).isFalse();
            assertThat(scenario.queries.get()).isEqualTo(1);
            scenario.release.countDown();
            assertThat(loader.result()).isEqualTo(302);
            verify(scenario.cache, times(4)).find("Ab12");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 403})
    void eachWaiterRechecksExpiryAfterTheLoaderTookItsSnapshot(int status) throws Exception {
        try (Scenario scenario = new Scenario(Duration.ofSeconds(5))) {
            scenario.blockFirstQuery(mapping(status, NOW.plusSeconds(1)));
            CountDownLatch refillEntered = new CountDownLatch(1);
            CountDownLatch refillRelease = new CountDownLatch(1);
            doAnswer(call -> {
                refillEntered.countDown();
                assertThat(refillRelease.await(5, TimeUnit.SECONDS)).isTrue();
                return true;
            }).when(scenario.cache).storeIfVersion(eq("Ab12"), anyString(),
                    eq(status == 302 ? RedirectCacheRead.Status.REDIRECT : RedirectCacheRead.Status.DISABLED), any());
            try {
                Request first = scenario.start("Ab12");
                scenario.awaitQuery();
                Request second = scenario.start("Ab12");
                second.awaitWaiting();
                scenario.release.countDown();
                assertThat(refillEntered.await(5, TimeUnit.SECONDS)).isTrue();
                scenario.now.set(NOW.plusSeconds(1));
                refillRelease.countDown();
                assertThat(first.result()).isEqualTo(410);
                assertThat(second.result()).isEqualTo(410);
                assertThat(scenario.queries.get()).isEqualTo(1);
            } finally {
                refillRelease.countDown();
            }
        }
    }

    @Test
    void databaseFailureIsSharedAndCleanedUpForRetry() throws Exception {
        try (Scenario scenario = new Scenario(Duration.ofSeconds(5))) {
            IllegalStateException failure = new IllegalStateException("MySQL failed");
            when(scenario.mapper.selectById(anyString())).thenAnswer(call -> {
                if (scenario.queries.incrementAndGet() == 1) {
                    scenario.entered.countDown();
                    assertThat(scenario.release.await(5, TimeUnit.SECONDS)).isTrue();
                    throw failure;
                }
                return mapping(302, null);
            });
            Request first = scenario.start("Ab12");
            scenario.awaitQuery();
            Request second = scenario.start("Ab12");
            second.awaitWaiting();
            scenario.release.countDown();
            assertThatThrownBy(first::result).hasCause(failure);
            assertThatThrownBy(second::result).hasCause(failure);
            verify(scenario.cache, never()).storeIfVersion(anyString(), anyString(), any(), any());
            assertThat(scenario.status("Ab12")).isEqualTo(302);
            assertThat(scenario.queries.get()).isEqualTo(2);
        }
    }

    @Test
    void unconfirmedVersionDoesNotJoinAnOlderLoadOrRefill() throws Exception {
        try (Scenario scenario = new Scenario(Duration.ofSeconds(5))) {
            scenario.blockFirstQuery(null, mapping(302, null));
            Request old = scenario.start("Ab12");
            scenario.awaitQuery();
            when(scenario.cache.find("Ab12")).thenThrow(new IllegalStateException("Redis offline"));
            assertThat(scenario.status("Ab12")).isEqualTo(302);
            assertThat(scenario.status("Ab12")).isEqualTo(302);
            assertThat(scenario.queries.get()).isEqualTo(3);
            verify(scenario.cache, never()).storeIfVersion(anyString(), anyString(), any(), any());
            scenario.release.countDown();
            assertThat(old.result()).isEqualTo(404);
        }
    }

    @Test
    void lostEntryInitializesANewVersionAndDoesNotJoinOldTask() throws Exception {
        try (Scenario scenario = new Scenario(Duration.ofSeconds(5))) {
            scenario.blockFirstQuery(null, mapping(302, null));
            Request old = scenario.start("Ab12");
            scenario.awaitQuery();
            scenario.generation.set("version-after-eviction");
            assertThat(scenario.status("Ab12")).isEqualTo(302);
            assertThat(old.task.isDone()).isFalse();
            scenario.release.countDown();
            assertThat(old.result()).isEqualTo(404);
            assertThat(scenario.queries.get()).isEqualTo(2);
        }
    }

    @Test
    void ownerRechecksCacheWhenAnEarlierRoundFinishedAfterItsInitialMiss() {
        try (Scenario scenario = new Scenario(Duration.ofSeconds(5))) {
            when(scenario.cache.find("Ab12")).thenReturn(RedirectCacheRead.miss("version-1"),
                    RedirectCacheRead.redirect("version-1", new RedirectCacheEntry("https://example.com/", null)));
            assertThat(scenario.status("Ab12")).isEqualTo(302);
            verifyNoInteractions(scenario.mapper);
        }
    }

    @Test
    void waitBudgetMustBePositiveAndFitInNanoseconds() {
        try (Scenario scenario = new Scenario(Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> scenario.service(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> scenario.service(Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> scenario.service(Duration.ofSeconds(Long.MAX_VALUE)))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    private static ShortLinkEntity mapping(int status, Instant expiry) {
        if (status == 404) return null;
        ShortLinkEntity entity = new ShortLinkEntity();
        entity.setOriginalUrl("https://example.com/");
        entity.setEnabled(status != 403);
        Instant expiresAt = status == 410 ? NOW : expiry;
        entity.setExpiresAt(expiresAt == null ? null : LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        return entity;
    }

    private static int status(ShortLinkService service, String code) {
        try {
            assertThat(service.findOriginalUrl(code)).isEqualTo("https://example.com/");
            return 302;
        } catch (LinkNotFoundException exception) {
            return 404;
        } catch (LinkDisabledException exception) {
            return 403;
        } catch (LinkExpiredException exception) {
            return 410;
        }
    }

    private record Request(FutureTask<Integer> task, Thread thread) {
        int result() throws Exception { return task.get(5, TimeUnit.SECONDS); }
        void awaitWaiting() {
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(1))
                    .untilAsserted(() -> assertThat(thread.getState()).isEqualTo(Thread.State.TIMED_WAITING));
        }
    }

    private static class Scenario implements AutoCloseable {
        final ShortLinkMapper mapper = mock(ShortLinkMapper.class);
        final RedirectCache cache = mock(RedirectCache.class);
        final Clock clock = mock(Clock.class);
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        final AtomicReference<String> generation = new AtomicReference<>("version-1");
        final AtomicInteger queries = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<Request> requests = new ArrayList<>();
        final ShortLinkService service;

        Scenario(Duration wait) {
            when(clock.instant()).thenAnswer(call -> now.get());
            when(cache.find(anyString())).thenAnswer(call -> RedirectCacheRead.miss(generation.get()));
            service = service(wait);
        }
        ShortLinkService service(Duration wait) {
            return new ShortLinkService(mapper, mock(ShortCodeIdIssuer.class),
                    new PermutedShortCodeEncoder(), clock, cache, wait);
        }
        void blockFirstQuery(ShortLinkEntity first) {
            blockFirstQuery(first, first);
        }
        void blockFirstQuery(ShortLinkEntity first, ShortLinkEntity later) {
            when(mapper.selectById(anyString())).thenAnswer(call -> {
                if (queries.incrementAndGet() == 1) {
                    entered.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return first;
                }
                return later;
            });
        }
        void awaitQuery() throws InterruptedException {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        }
        int status(String code) { return RedirectLoadCoalescingTest.status(service, code); }
        Request start(String code) { return start(code, service); }
        Request start(String code, ShortLinkService target) {
            FutureTask<Integer> task = new FutureTask<>(() -> RedirectLoadCoalescingTest.status(target, code));
            Request request = new Request(task, new Thread(task));
            requests.add(request);
            request.thread.start();
            return request;
        }
        @Override public void close() {
            release.countDown();
            for (Request request : requests) {
                try {
                    request.thread.join(5000);
                    assertThat(request.thread.isAlive()).isFalse();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
        }
    }
}
