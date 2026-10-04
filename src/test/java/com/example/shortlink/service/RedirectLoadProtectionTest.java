package com.example.shortlink.service;

import com.example.shortlink.cache.*;
import com.example.shortlink.persistence.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedirectLoadProtectionTest {
    @Test void cacheHitsAndSharedWaitersUseNoAdditionalQueryPermitAndPermitEndsAtQueryCompletion() throws Exception {
        var mapper = mock(ShortLinkMapper.class);
        var cache = mock(RedirectCache.class);
        when(cache.find(anyString())).thenReturn(RedirectCacheRead.miss("generation"));
        var mapping = new ShortLinkEntity(); mapping.setEnabled(true); mapping.setOriginalUrl("https://example.com/");
        CountDownLatch queryEntered = new CountDownLatch(1), queryRelease = new CountDownLatch(1);
        CountDownLatch cacheEntered = new CountDownLatch(1), cacheRelease = new CountDownLatch(1);
        when(mapper.selectById("Ab12")).thenAnswer(call -> {
            queryEntered.countDown(); assertThat(queryRelease.await(5, TimeUnit.SECONDS)).isTrue(); return mapping;
        });
        doAnswer(call -> { cacheEntered.countDown(); assertThat(cacheRelease.await(5, TimeUnit.SECONDS)).isTrue(); return true; })
                .when(cache).storeIfVersion(eq("Ab12"), anyString(), any(), any());
        var service = new RedirectService(mapper, Clock.systemUTC(), cache,
                new RedirectCacheProperties(true,"5m","30s","5m","15s","5s"), new RedirectLoadProperties(1));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var owner = executor.submit(() -> service.decide("Ab12"));
            assertThat(queryEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var waiter = executor.submit(() -> service.decide("Ab12"));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(cache, atLeast(3)).find("Ab12"));
            when(cache.find("Hit1")).thenReturn(RedirectCacheRead.redirect("other", new RedirectCacheEntry("https://cache.example/", null)));
            assertThat(service.decide("Hit1").originalUrl()).isEqualTo("https://cache.example/");
            verify(mapper, never()).selectById("Hit1");
            assertThatThrownBy(() -> service.decide("Cd34")).hasMessage("Redirect database load capacity is busy.");
            queryRelease.countDown();
            assertThat(cacheEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.decide("Cd34")).isInstanceOf(com.example.shortlink.service.error.LinkNotFoundException.class);
            verify(mapper).selectById("Cd34");
            cacheRelease.countDown();
            assertThat(owner.get(5, TimeUnit.SECONDS).originalUrl()).isEqualTo("https://example.com/");
            assertThat(waiter.get(5, TimeUnit.SECONDS).originalUrl()).isEqualTo("https://example.com/");
            verify(mapper, times(1)).selectById("Ab12");
        } finally { queryRelease.countDown(); cacheRelease.countDown(); executor.shutdownNow(); }
    }
    @Test void timedOutWaiterAndUnconfirmedVersionLoadsRespectTheSameBudget() throws Exception {
        for (boolean unavailable : List.of(false, true)) {
            var mapper = mock(ShortLinkMapper.class); var cache = mock(RedirectCache.class);
            if (unavailable) when(cache.find(anyString())).thenThrow(new IllegalStateException("offline"));
            else when(cache.find(anyString())).thenReturn(RedirectCacheRead.miss("generation"));
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            when(mapper.selectById(anyString())).thenAnswer(call -> { entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return null; });
            var service = new RedirectService(mapper, Clock.systemUTC(), cache,
                    new RedirectCacheProperties(true,"5m","30s","5m","15s","30ms"), new RedirectLoadProperties(1));
            var executor = Executors.newSingleThreadExecutor();
            try {
                var owner = executor.submit(() -> service.decide("Ab12"));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.decide("Ab12")).hasMessage("Redirect database load capacity is busy.");
                verify(mapper, times(1)).selectById("Ab12");
                release.countDown();
                assertThatThrownBy(() -> owner.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(com.example.shortlink.service.error.LinkNotFoundException.class);
                assertThatThrownBy(() -> service.decide("Cd34")).isInstanceOf(com.example.shortlink.service.error.LinkNotFoundException.class);
                verify(mapper).selectById("Cd34");
            } finally { release.countDown(); executor.shutdownNow(); }
        }
    }
    @Test void fourActualQueriesRejectFifthWithoutQueuingAndReleaseOnFailure() throws Exception {
        var mapper = mock(ShortLinkMapper.class);
        var cache = mock(RedirectCache.class);
        when(cache.find(anyString())).thenReturn(RedirectCacheRead.miss("generation"));
        CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
        when(mapper.selectById(anyString())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            throw new IllegalStateException("controlled query failure");
        });
        var service = new RedirectService(mapper, Clock.systemUTC(), cache,
                new RedirectCacheProperties(true,"5m","30s","5m","15s","5s"));
        var executor = Executors.newFixedThreadPool(4);
        List<Future<?>> queries = new ArrayList<>();
        try {
            for (String code : List.of("Ab12","Cd34","Ef56","Gh78")) queries.add(executor.submit(() -> service.decide(code)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.decide("Ij90"))
                    .hasMessage("Redirect database load capacity is busy.");
            verify(mapper, never()).selectById("Ij90");
            verify(cache, never()).storeIfVersion(eq("Ij90"), anyString(), any(), any());
            release.countDown();
            for (var query : queries) assertThatThrownBy(() -> query.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            doReturn(null).when(mapper).selectById("Ij90");
            assertThatThrownBy(() -> service.decide("Ij90")).isInstanceOf(com.example.shortlink.service.error.LinkNotFoundException.class);
            verify(mapper).selectById("Ij90");
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}


