package com.example.shortlink;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"short-link.stats.enabled=true",
        "short-link.stats.visitor-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "short-link.stats.visitor-key-version=1", "short-link.base-url=https://short.local",
        "short-link.redirect-cache.load-wait=5s"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ShortLinkApiTest.ControlledTimeConfiguration.class)
class VisitStatisticsApiTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @Autowired ShortLinkApiTest.ControllableClock clock;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.example.shortlink.stats.VisitRecorder recorder;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.example.shortlink.persistence.ShortLinkMapper mapper;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.example.shortlink.cache.RedirectCache cache;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("statsDataSource")
    com.zaxxer.hikari.HikariDataSource statsPool;
    @Autowired javax.sql.DataSource corePool;

    @Test
    void sharedLoadProducesTwoVisitsWithSeparateDecisionTimes() throws Exception {
        var mapping = mapper.selectById("Ab12");
        org.mockito.Mockito.clearInvocations(mapper);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return mapping;
        }).when(mapper).selectById("Ab12");
        clock.advanceAfterEachRead(java.time.Duration.ofMillis(1));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var waiter = new java.util.concurrent.atomic.AtomicReference<Thread>();
        try {
            var first = executor.submit(() -> http.perform(get("/s/Ab12")).andExpect(status().isFound()));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                waiter.set(Thread.currentThread());
                return http.perform(get("/s/Ab12")).andExpect(status().isFound());
            });
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                    waiter.get() != null && waiter.get().getState() == Thread.State.TIMED_WAITING);
            release.countDown();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            org.mockito.Mockito.verify(mapper).selectById("Ab12");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT COUNT(DISTINCT occurred_at) FROM short_link_visit_log", Integer.class)).isEqualTo(2);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void realLockTimeoutAndCapacityDropPreserveRedirectAndReleasePermits() throws Exception {
        http.perform(get("/s/Ab12")).andExpect(status().isFound());
        byte[] id = db.queryForObject("SELECT event_id FROM short_link_visit_log", byte[].class);
        var buffer = java.nio.ByteBuffer.wrap(id);
        var event = new com.example.shortlink.stats.VisitEvent(new java.util.UUID(buffer.getLong(), buffer.getLong()),
                "Ab12", clock.instant(), java.time.LocalDate.of(2026,9,30), new byte[32], 1, null, null, null);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try (var lock = corePool.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT id FROM short_link_visit_log WHERE event_id=? FOR UPDATE")) {
                statement.setBytes(1, id);
                try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); }
            }
            var first = executor.submit(() -> recorder.record(event));
            var second = executor.submit(() -> recorder.record(event));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(() ->
                    statsPool.getHikariPoolMXBean().getActiveConnections() == 2);
            long start = System.nanoTime();
            http.perform(get("/s/Ab12")).andExpect(status().isFound()).andExpect(header().exists("Set-Cookie"));
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-start)).isLessThan(java.time.Duration.ofMillis(500));
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            lock.rollback();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(1);
            recorder.record(event); // Internal replay after the lock releases is an ordinary duplicate.
            assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(1);
            http.perform(get("/s/Ab12")).andExpect(status().isFound());
            assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(2);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void identityIsIsolatedByCaseSensitiveCodeAndMetadataIsMinimized() throws Exception {
        for (String code : new String[]{"Ab12", "Ab12", "ab12"}) {
            http.perform(get("/s/" + code).header("Cookie", "sl_visitor=AAAAAAAAAAAAAAAAAAAAAA")
                            .header("X-Forwarded-For", "203.0.113.42").header("Forwarded", "for=203.0.113.42")
                            .header("User-Agent", "a\u0001" + "😀".repeat(600))
                            .header("Referer", "https://user:secret@例子.测试.:443/path?secret=yes#fragment")
                            .with(request -> { request.setRemoteAddr("::ffff:192.168.7.99"); return request; }))
                    .andExpect(status().isFound()).andExpect(header().doesNotExist("Set-Cookie"));
        }
        assertThat(db.queryForObject("SELECT COUNT(DISTINCT visitor_hash) FROM short_link_visit_log", Integer.class)).isEqualTo(2);
        assertThat(db.queryForList("SELECT peer_ip_network FROM short_link_visit_log", String.class)).containsOnly("192.168.7.0/24");
        assertThat(db.queryForList("SELECT referer_host FROM short_link_visit_log", String.class)).containsOnly("xn--fsqu00a.xn--0zwm56d");
        String ua = db.queryForObject("SELECT user_agent FROM short_link_visit_log LIMIT 1", String.class);
        assertThat(ua.codePointCount(0, ua.length())).isEqualTo(512);
        assertThat(ua).startsWith("a😀").doesNotContain("\u0001");
    }

    @Test
    void noncanonicalAndDuplicateCookiesAreRebuiltAndHttpsSetsSecure() throws Exception {
        for (String cookie : new String[]{"sl_visitor=AAAAAAAAAAAAAAAAAAAAAB", "sl_visitor=bad",
                "sl_visitor=AAAAAAAAAAAAAAAAAAAAAA; sl_visitor=AAAAAAAAAAAAAAAAAAAAAA"}) {
            http.perform(get("/s/Ab12").secure(true).header("Cookie", cookie))
                    .andExpect(status().isFound()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Secure")));
        }
        assertThat(db.queryForObject("SELECT COUNT(DISTINCT visitor_hash) FROM short_link_visit_log", Integer.class)).isEqualTo(3);
    }

    @Test
    void writeFailureAfterDecisionKeeps302AndFrozenDayWithoutReadingClockAgain() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            clock.setInstant(Instant.parse("2026-09-30T16:00:01Z"));
            db.update("UPDATE short_link SET enabled=false WHERE short_code='Ab12'");
            invocation.callRealMethod();
            throw new IllegalStateException("Confirmation lost");
        }).when(recorder).record(org.mockito.ArgumentMatchers.any());
        http.perform(get("/s/Ab12")).andExpect(status().isFound()).andExpect(header().exists("Set-Cookie"));
        org.mockito.Mockito.verify(recorder).record(org.mockito.ArgumentMatchers.any());
        assertThat(db.queryForObject("SELECT stat_date FROM short_link_visit_log", java.sql.Date.class).toLocalDate())
                .isEqualTo(java.time.LocalDate.of(2026,9,30));
        assertThat(db.queryForObject("SELECT occurred_at FROM short_link_visit_log", java.time.LocalDateTime.class))
                .isEqualTo(java.time.LocalDateTime.parse("2026-09-30T15:59:59.987"));
    }

    @Test
    void hitAndMissEachRecordButOnlyMissReadsMapping() throws Exception {
        http.perform(get("/s/Ab12")).andExpect(status().isFound());
        org.mockito.Mockito.doReturn(com.example.shortlink.cache.RedirectCacheRead.result(
                com.example.shortlink.cache.RedirectCacheRead.Status.REDIRECT, "generation",
                new com.example.shortlink.cache.RedirectCacheEntry("https://example.com/", null))).when(cache).find("Ab12");
        http.perform(get("/s/Ab12")).andExpect(status().isFound());
        org.mockito.Mockito.verify(mapper).selectById("Ab12");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(2);
    }

    @BeforeEach
    void reset() {
        db.update("DELETE FROM short_link_visit_log");
        db.update("DELETE FROM short_link");
        clock.reset(Instant.parse("2026-09-30T15:59:59.987654321Z"));
        for (String code : new String[]{"Ab12", "ab12"}) {
            db.update("INSERT INTO short_link(short_code,original_url,created_at,enabled) VALUES (?, ?, UTC_TIMESTAMP(3),true)",
                    code, "https://example.com/");
        }
    }

    @Test
    void repeatedGetsRecordIndependentEventsAndReuseTheVisitorWithoutRenewal() throws Exception {
        var first = http.perform(get("/s/Ab12"))
                .andExpect(status().isFound()).andExpect(header().string("Location", "https://example.com/"))
                .andReturn().getResponse();
        Cookie visitor = first.getCookie("sl_visitor");
        assertThat(visitor).isNotNull();
        assertThat(visitor.getValue()).matches("[A-Za-z0-9_-]{22}");
        assertThat(first.getHeader("Set-Cookie")).contains("Path=/s", "Max-Age=2592000", "HttpOnly", "SameSite=Lax", "Secure")
                .doesNotContain("Domain=");
        http.perform(get("/s/Ab12").cookie(visitor)).andExpect(status().isFound())
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(DISTINCT visitor_hash) FROM short_link_visit_log", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(DISTINCT event_id) FROM short_link_visit_log", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT MIN(stat_date) FROM short_link_visit_log", java.sql.Date.class).toLocalDate())
                .isEqualTo(java.time.LocalDate.of(2026,9,30));
    }

    @Test
    void headAndRejectionsDoNotSetCookieOrWriteVisits() throws Exception {
        http.perform(head("/s/Ab12")).andExpect(status().isFound()).andExpect(header().doesNotExist("Set-Cookie"));
        http.perform(get("/s/missing")).andExpect(status().isNotFound()).andExpect(header().doesNotExist("Set-Cookie"));
        http.perform(get("/s/!invalid")).andExpect(status().isNotFound()).andExpect(header().doesNotExist("Set-Cookie"));
        db.update("UPDATE short_link SET enabled=false WHERE short_code='Ab12'");
        http.perform(get("/s/Ab12")).andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
        db.update("UPDATE short_link SET expires_at='2026-09-30 15:59:59' WHERE short_code='Ab12'");
        http.perform(get("/s/Ab12")).andExpect(status().isGone()).andExpect(header().doesNotExist("Set-Cookie"));
        org.mockito.Mockito.doThrow(new IllegalStateException("core read failed")).when(mapper).selectById("ab12");
        http.perform(get("/s/ab12")).andExpect(status().isInternalServerError()).andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isZero();
    }

    @Test
    void ipv6AndInvalidOptionalMetadataDoNotBlockRedirects() throws Exception {
        http.perform(get("/s/Ab12").header("Referer", "about:blank").with(request -> {
            request.setRemoteAddr("2001:db8:1234:5678::99%eth0"); return request;
        })).andExpect(status().isFound());
        assertThat(db.queryForObject("SELECT peer_ip_network FROM short_link_visit_log", String.class))
                .isEqualTo("2001:db8:1234:0:0:0:0:0/48");
        assertThat(db.queryForObject("SELECT referer_host FROM short_link_visit_log", String.class)).isNull();
        db.update("DELETE FROM short_link_visit_log");
        http.perform(get("/s/Ab12").header("Referer", "https://example.com:invalid/secrets").with(request -> {
            request.setRemoteAddr("do-not-resolve.example"); return request;
        })).andExpect(status().isFound());
        assertThat(db.queryForObject("SELECT peer_ip_network FROM short_link_visit_log", String.class)).isNull();
        assertThat(db.queryForObject("SELECT referer_host FROM short_link_visit_log", String.class)).isNull();
        assertThat(db.queryForObject("SELECT user_agent FROM short_link_visit_log", String.class)).isNull();
    }
}
