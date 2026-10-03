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

@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"short-link.stats.rabbit.consumer-enabled=true","short-link.stats.rabbit.virtual-host=${SHORT_LINK_STATS_RABBIT_VIRTUAL_HOST:/}","short-link.stats.enabled=true",
        "short-link.stats.visitor-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "short-link.stats.visitor-key-version=1", "short-link.base-url=https://short.local",
        "short-link.internal-token=0123456789abcdef0123456789abcdef",
        "short-link.stats.socket-timeout-ms=5000", "short-link.stats.statement-timeout-seconds=3",
        "short-link.redirect-cache.load-wait=5s", "short-link.stats.rabbit.port=${RABBIT_TEST_PORT:5672}"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ShortLinkApiTest.ControlledTimeConfiguration.class)
class VisitStatisticsApiTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @Autowired ShortLinkApiTest.ControllableClock clock;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.example.shortlink.stats.MySqlVisitPersistence recorder;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.example.shortlink.stats.messaging.AsyncVisitRecorder collector;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.example.shortlink.persistence.ShortLinkMapper mapper;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.example.shortlink.cache.RedirectCache cache;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("statsDataSource")
    com.zaxxer.hikari.HikariDataSource statsPool;
    @Autowired javax.sql.DataSource corePool;
    @Autowired com.example.shortlink.stats.VisitWriteObservations observations;

    @Test
    void enabledCollectionIsReportedAndStatisticsRequestsNeverCollectVisits() throws Exception {
        http.perform(get("/s/Ab12")).andExpect(status().isFound());
        awaitCount(1);
        http.perform(get("/api/internal/links/Ab12/stats")
                        .header("X-Internal-Token", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.collectionEnabled").value(true))
                .andExpect(jsonPath("$.pv").value(1)).andExpect(jsonPath("$.uv").value(1))
                .andExpect(header().doesNotExist("Set-Cookie"));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(1));
    }
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean(name = "statsDataSource")
    com.zaxxer.hikari.HikariDataSource controlledStatsPool;

    @Test
    void savedButLostSqlConfirmationIsUncertainWithoutRetryAndKeepsRedirect() throws Exception {
        var before = observations.snapshot();
        org.mockito.Mockito.doAnswer(invocation -> {
            var real = (java.sql.Connection) invocation.callRealMethod();
            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                        Object result = invoke(real, method, args);
                        if (!method.getName().equals("prepareStatement")) return result;
                        var statement = (java.sql.PreparedStatement) result;
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{java.sql.PreparedStatement.class}, (p, m, a) -> {
                                    Object value = invoke(statement, m, a);
                                    if (m.getName().equals("executeUpdate")) throw new java.sql.SQLException("private reply lost", "08S01");
                                    return value;
                                });
                    });
        }).when(controlledStatsPool).getConnection();
        http.perform(get("/s/Ab12")).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/"))
                .andExpect(header().string("Cache-Control", "no-store"));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(1));
        assertThat(observations.snapshot().outcomes().get(com.example.shortlink.stats.VisitWriteObservations.Outcome.UNCERTAIN))
                .isEqualTo(before.outcomes().get(com.example.shortlink.stats.VisitWriteObservations.Outcome.UNCERTAIN) + 1);
        assertThat(observations.snapshot().attempted()).isEqualTo(before.attempted() + 1);
        org.mockito.Mockito.doCallRealMethod().when(controlledStatsPool).getConnection();
        http.perform(get("/s/Ab12")).andExpect(status().isFound());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(2));
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    }

    @Test
    void exhaustedStatisticsPoolDoesNotTakeOverCoreCreationIssuanceOrStateTransactions() throws Exception {
        http.perform(get("/s/Ab12")).andExpect(status().isFound());
        awaitCount(1);
        var before = observations.snapshot();
        var held = new java.util.ArrayList<java.sql.Connection>();
        try {
            for (int i = 0; i < 4; i++) held.add(statsPool.getConnection());
            assertThat(observations.snapshot().poolActive()).isEqualTo(4);
            var created = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/links")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"originalUrl\":\"https://example.com/core\"}"))
                    .andExpect(status().isCreated()).andReturn();
            String code = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(created.getResponse().getContentAsString()).get("shortCode").asText();
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/links/" + code + "/enabled")
                    .header("X-Internal-Token", "0123456789abcdef0123456789abcdef")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                    .andExpect(status().isOk());
            assertThat(db.queryForObject("SELECT enabled FROM short_link WHERE short_code=?", Boolean.class, code)).isFalse();
            long start = System.nanoTime();
            http.perform(get("/s/Ab12")).andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://example.com/"))
                    .andExpect(header().string("Cache-Control", "no-store"));
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofMillis(800));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(observations.snapshot().outcomes().get(com.example.shortlink.stats.VisitWriteObservations.Outcome.FAILED))
                            .isGreaterThanOrEqualTo(before.outcomes().get(com.example.shortlink.stats.VisitWriteObservations.Outcome.FAILED) + 3));
            assertThat(observations.snapshot().inFlight()).isZero();
        } finally { for (var connection : held) connection.close(); }
        http.perform(get("/s/Ab12")).andExpect(status().isFound());
        awaitCount(2);
        assertThat(observations.snapshot().inFlight()).isZero();
    }

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
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(2));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(DISTINCT occurred_at) FROM short_link_visit_log", Integer.class)).isEqualTo(2));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void realLockTimeoutAndCapacityDropPreserveRedirectAndReleasePermits() throws Exception {
        http.perform(get("/s/Ab12")).andExpect(status().isFound());
        awaitCount(1);
        byte[] id = db.queryForObject("SELECT event_id FROM short_link_visit_log", byte[].class);
        var buffer = java.nio.ByteBuffer.wrap(id);
        var event = new com.example.shortlink.stats.VisitEvent(new java.util.UUID(buffer.getLong(), buffer.getLong()),
                "Ab12", clock.instant(), java.time.LocalDate.of(2026,9,30), new byte[32], 1, null, null, null);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var before = observations.snapshot();
        try (var lock = corePool.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT id FROM short_link_visit_log WHERE event_id=? FOR UPDATE")) {
                statement.setBytes(1, id);
                try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); }
            }
            var first = executor.submit(() -> recorder.persist(event));
            var second = executor.submit(() -> recorder.persist(event));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(() ->
                    statsPool.getHikariPoolMXBean().getActiveConnections() == 2);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    ((com.example.shortlink.stats.VisitPersistence) recorder).persist(event))
                    .isInstanceOf(com.example.shortlink.stats.VisitPersistenceException.class)
                    .extracting("failure").isEqualTo(com.example.shortlink.stats.VisitPersistenceException.Failure.BUSY);
            long start = System.nanoTime();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> recorder.persist(event))
                    .isInstanceOf(com.example.shortlink.stats.VisitPersistenceException.class)
                    .extracting("failure").isEqualTo(com.example.shortlink.stats.VisitPersistenceException.Failure.BUSY);
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> assertThat(observations.snapshot().outcomes().get(com.example.shortlink.stats.VisitWriteObservations.Outcome.DROPPED))
                    .isEqualTo(before.outcomes().get(com.example.shortlink.stats.VisitWriteObservations.Outcome.DROPPED) + 2));
            assertThat(first.isCancelled()).isFalse();
            assertThat(second.isCancelled()).isFalse();
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-start)).isLessThan(java.time.Duration.ofMillis(500));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> first.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .rootCause().isInstanceOf(com.example.shortlink.stats.VisitPersistenceException.class)
                    .extracting("failure").isEqualTo(com.example.shortlink.stats.VisitPersistenceException.Failure.TRANSIENT);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> second.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .rootCause().isInstanceOf(com.example.shortlink.stats.VisitPersistenceException.class)
                    .extracting("failure").isEqualTo(com.example.shortlink.stats.VisitPersistenceException.Failure.TRANSIENT);
            assertThat(observations.snapshot().categories().get(com.example.shortlink.stats.VisitWriteObservations.Category.TIMEOUT))
                    .isEqualTo(before.categories().get(com.example.shortlink.stats.VisitWriteObservations.Category.TIMEOUT) + 2);
            lock.rollback();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(1));
            assertThat(recorder.persist(event)).isEqualTo(com.example.shortlink.stats.VisitPersistence.Outcome.DUPLICATE);
            assertThat(observations.snapshot().outcomes().get(com.example.shortlink.stats.VisitWriteObservations.Outcome.DUPLICATE))
                    .isEqualTo(before.outcomes().get(com.example.shortlink.stats.VisitWriteObservations.Outcome.DUPLICATE) + 1);
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(1));
            http.perform(get("/s/Ab12")).andExpect(status().isFound());
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(2));
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
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(DISTINCT visitor_hash) FROM short_link_visit_log", Integer.class)).isEqualTo(2));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForList("SELECT peer_ip_network FROM short_link_visit_log", String.class)).containsOnly("192.168.7.0/24"));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForList("SELECT referer_host FROM short_link_visit_log", String.class)).containsOnly("xn--fsqu00a.xn--0zwm56d"));
        awaitCount(3);
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
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(DISTINCT visitor_hash) FROM short_link_visit_log", Integer.class)).isEqualTo(3));
    }

    @Test
    void writeFailureAfterDecisionKeeps302AndFrozenDayWithoutReadingClockAgain() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            clock.setInstant(Instant.parse("2026-09-30T16:00:01Z"));
            db.update("UPDATE short_link SET enabled=false WHERE short_code='Ab12'");
            invocation.callRealMethod();
            throw new IllegalStateException("Confirmation lost");
        }).when(collector).record(org.mockito.ArgumentMatchers.any());
        http.perform(get("/s/Ab12")).andExpect(status().isFound()).andExpect(header().exists("Set-Cookie"));
        org.mockito.Mockito.verify(collector).record(org.mockito.ArgumentMatchers.any());
        awaitCount(1);
        assertThat(db.queryForObject("SELECT stat_date FROM short_link_visit_log", java.sql.Date.class).toLocalDate())
                .isEqualTo(java.time.LocalDate.of(2026,9,30));
        awaitCount(1);
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
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(2));
    }

    @Test
    void persistenceConfirmsSaveAndOnlyEventReplayAsDuplicate() {
        var event = new com.example.shortlink.stats.VisitEvent(java.util.UUID.randomUUID(),
                "Ab12", clock.instant(), java.time.LocalDate.of(2026,9,30), new byte[32], 1, null, null, null);
        var persistence = (com.example.shortlink.stats.VisitPersistence) recorder;
        assertThat(persistence.persist(event)).isEqualTo(com.example.shortlink.stats.VisitPersistence.Outcome.SAVED);
        assertThat(persistence.persist(event)).isEqualTo(com.example.shortlink.stats.VisitPersistence.Outcome.DUPLICATE);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(1);
    }
    @Test
    void persistenceRejectsOtherConstraintsAndWrongSqlAsPermanent() throws Exception {
        var persistence = (com.example.shortlink.stats.VisitPersistence) recorder;
        var invalid = new com.example.shortlink.stats.VisitEvent(java.util.UUID.randomUUID(),
                null, clock.instant(), java.time.LocalDate.of(2026,9,30), new byte[32], 1, null, null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> persistence.persist(invalid))
                .isInstanceOf(com.example.shortlink.stats.VisitPersistenceException.class)
                .extracting("failure").isEqualTo(com.example.shortlink.stats.VisitPersistenceException.Failure.PERMANENT);
        org.mockito.Mockito.doAnswer(invocation -> {
            var real = (java.sql.Connection) invocation.callRealMethod();
            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")) return real.prepareStatement("INSERT INTO absent_visit_table VALUES (1)");
                        return invoke(real, method, args);
                    });
        }).when(controlledStatsPool).getConnection();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> persistence.persist(invalid))
                .isInstanceOf(com.example.shortlink.stats.VisitPersistenceException.class)
                .extracting("failure").isEqualTo(com.example.shortlink.stats.VisitPersistenceException.Failure.PERMANENT);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isZero();
        assertThat(observations.snapshot().inFlight()).isZero();
    }
    @Test
    void persistenceExposesLostConfirmationButPreservesConfirmedSaveAfterCleanupFailure() throws Exception {
        var persistence = (com.example.shortlink.stats.VisitPersistence) recorder;
        for (String stage : new String[]{"executeUpdate", "close"}) {
            var event = new com.example.shortlink.stats.VisitEvent(java.util.UUID.randomUUID(),
                    "Ab12", clock.instant(), java.time.LocalDate.of(2026,9,30), new byte[32], 1, null, null, null);
            org.mockito.Mockito.doAnswer(invocation -> {
                var real = (java.sql.Connection) invocation.callRealMethod();
                return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                            Object result = invoke(real, method, args);
                            if (!method.getName().equals("prepareStatement")) return result;
                            var statement = (java.sql.PreparedStatement) result;
                            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[]{java.sql.PreparedStatement.class}, (p, m, a) -> {
                                        Object value = invoke(statement, m, a);
                                        if (m.getName().equals(stage)) throw new java.sql.SQLException("private reply lost", "08S01");
                                        return value;
                                    });
                        });
            }).when(controlledStatsPool).getConnection();
            if (stage.equals("executeUpdate")) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> persistence.persist(event))
                        .isInstanceOf(com.example.shortlink.stats.VisitPersistenceException.class)
                        .hasMessage("Visit persistence UNCERTAIN").hasNoCause()
                        .extracting("failure").isEqualTo(com.example.shortlink.stats.VisitPersistenceException.Failure.UNCERTAIN);
            } else assertThat(persistence.persist(event)).isEqualTo(com.example.shortlink.stats.VisitPersistence.Outcome.SAVED);
            org.mockito.Mockito.doCallRealMethod().when(controlledStatsPool).getConnection();
            assertThat(persistence.persist(event)).isEqualTo(com.example.shortlink.stats.VisitPersistence.Outcome.DUPLICATE);
            assertThat(observations.snapshot().inFlight()).isZero();
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(2);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(statsPool.getHikariPoolMXBean().getActiveConnections()).isZero());
    }

    @Test
    void persistenceExposesConnectionFailureBeforeExecutionAndReleasesAdmission() throws Exception {
        var event = new com.example.shortlink.stats.VisitEvent(java.util.UUID.randomUUID(),
                "Ab12", clock.instant(), java.time.LocalDate.of(2026,9,30), new byte[32], 1, null, null, null);
        var persistence = (com.example.shortlink.stats.VisitPersistence) recorder;
        org.mockito.Mockito.doThrow(new java.sql.SQLTransientConnectionException("secret pool detail"))
                .when(controlledStatsPool).getConnection();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> persistence.persist(event))
                .isInstanceOf(com.example.shortlink.stats.VisitPersistenceException.class)
                .hasMessage("Visit persistence TRANSIENT").hasNoCause()
                .extracting("failure").isEqualTo(com.example.shortlink.stats.VisitPersistenceException.Failure.TRANSIENT);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isZero();
        org.mockito.Mockito.doCallRealMethod().when(controlledStatsPool).getConnection();
        assertThat(persistence.persist(event)).isEqualTo(com.example.shortlink.stats.VisitPersistence.Outcome.SAVED);
        assertThat(observations.snapshot().inFlight()).isZero();
    }
    @Test
    void duplicateOnAnotherUniqueKeyIsPermanentAndNeverAcknowledged() {
        var persistence = (com.example.shortlink.stats.VisitPersistence) recorder;
        var first = new com.example.shortlink.stats.VisitEvent(java.util.UUID.randomUUID(),
                "Ab12", clock.instant(), java.time.LocalDate.of(2026,9,30), new byte[32], 1, null, null, null);
        var second = new com.example.shortlink.stats.VisitEvent(java.util.UUID.randomUUID(),
                "Ab12", clock.instant(), java.time.LocalDate.of(2026,9,30), new byte[32], 1, null, null, null);
        // A real alternate constraint exercises the driver's unique-key error contract.
        db.execute("ALTER TABLE short_link_visit_log ADD UNIQUE KEY uq_test_short_code (short_code)");
        try {
            assertThat(persistence.persist(first)).isEqualTo(com.example.shortlink.stats.VisitPersistence.Outcome.SAVED);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> persistence.persist(second))
                    .isInstanceOf(com.example.shortlink.stats.VisitPersistenceException.class)
                    .extracting("failure").isEqualTo(com.example.shortlink.stats.VisitPersistenceException.Failure.PERMANENT);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(1);
        } finally { db.execute("ALTER TABLE short_link_visit_log DROP INDEX uq_test_short_code"); }
    }
    private void awaitCount(int count) { org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(count)); }
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
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isEqualTo(2));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(DISTINCT visitor_hash) FROM short_link_visit_log", Integer.class)).isEqualTo(1));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(DISTINCT event_id) FROM short_link_visit_log", Integer.class)).isEqualTo(2));
        awaitCount(2);
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
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Integer.class)).isZero());
    }

    @Test
    void refererRejectsMalformedAuthorityAndKeepsLegalMappedIpv6() throws Exception {
        http.perform(get("/s/Ab12").header("Referer", "https://a@b@example.com/private"))
                .andExpect(status().isFound());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT referer_host FROM short_link_visit_log", String.class)).isNull());
        db.update("DELETE FROM short_link_visit_log");
        http.perform(get("/s/Ab12").header("Referer", "https://[::ffff:192.168.1.1]/private"))
                .andExpect(status().isFound());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT referer_host FROM short_link_visit_log", String.class)).isEqualTo("192.168.1.1"));
    }

    @Test
    void ipv6AndInvalidOptionalMetadataDoNotBlockRedirects() throws Exception {
        http.perform(get("/s/Ab12").header("Referer", "about:blank").with(request -> {
            request.setRemoteAddr("2001:db8:1234:5678::99%eth0"); return request;
        })).andExpect(status().isFound());
        awaitCount(1);
        assertThat(db.queryForObject("SELECT peer_ip_network FROM short_link_visit_log", String.class))
                .isEqualTo("2001:db8:1234:0:0:0:0:0/48");
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT referer_host FROM short_link_visit_log", String.class)).isNull());
        db.update("DELETE FROM short_link_visit_log");
        http.perform(get("/s/Ab12").header("Referer", "https://example.com:invalid/secrets").with(request -> {
            request.setRemoteAddr("do-not-resolve.example"); return request;
        })).andExpect(status().isFound());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT peer_ip_network FROM short_link_visit_log", String.class)).isNull());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT referer_host FROM short_link_visit_log", String.class)).isNull());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject("SELECT user_agent FROM short_link_visit_log", String.class)).isNull());
    }
}
