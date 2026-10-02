package com.example.shortlink;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"short-link.internal-token=0123456789abcdef0123456789abcdef",
        "short-link.stats.socket-timeout-ms=5000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ShortLinkApiTest.ControlledTimeConfiguration.class)
class VisitStatsQueryApiTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @Autowired ShortLinkApiTest.ControllableClock clock;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired com.example.shortlink.stats.VisitQueryObservations queryObservations;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean(name = "statsDataSource")
    com.zaxxer.hikari.HikariDataSource pool;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.example.shortlink.persistence.ShortLinkMapper mapper;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.example.shortlink.cache.RedirectCache cache;

    @BeforeEach
    void reset() {
        db.update("DELETE FROM short_link_visit_log");
        db.update("DELETE FROM short_link");
        clock.reset(Instant.parse("2026-09-30T16:00:00Z"));
        db.update("INSERT INTO short_link(short_code,original_url,created_at,enabled) VALUES ('Ab12','https://example.com',UTC_TIMESTAMP(3),false)");
    }

    @Test
    void defaultWindowReturnsContinuousZeroDaysAndExplicitCollectionSemantics() throws Exception {
        http.perform(get("/api/internal/links/Ab12/stats")
                .header("X-Internal-Token", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.shortCode").value("Ab12"))
                .andExpect(jsonPath("$.from").value("2026-09-25"))
                .andExpect(jsonPath("$.to").value("2026-10-01"))
                .andExpect(jsonPath("$.pv").value(0)).andExpect(jsonPath("$.uv").value(0))
                .andExpect(jsonPath("$.timeZone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.uvBasis").value("anonymous-cookie"))
                .andExpect(jsonPath("$.collectionPolicy").value("best-effort"))
                .andExpect(jsonPath("$.collectionEnabled").value(false))
                .andExpect(jsonPath("$.identityVersions").isEmpty())
                .andExpect(jsonPath("$.generatedAt").value("2026-09-30T16:00:00Z"))
                .andExpect(jsonPath("$.daily.length()").value(7))
                .andExpect(jsonPath("$.daily[0].isOngoing").value(false))
                .andExpect(jsonPath("$.daily[6].isOngoing").value(true));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder stats(String code) {
        return get("/api/internal/links/" + code + "/stats")
                .header("X-Internal-Token", "0123456789abcdef0123456789abcdef");
    }

    private void visit(String code, String utc, int version, int visitor) {
        byte[] hash = new byte[32];
        hash[0] = (byte) visitor;
        db.update("""
                INSERT INTO short_link_visit_log(event_id,short_code,occurred_at,stat_date,visitor_hash,visitor_key_version)
                VALUES (UUID_TO_BIN(UUID()),?,?,DATE(DATE_ADD(?,INTERVAL 8 HOUR)),?,?)
                """, code, utc, utc, hash, version);
    }

    @Test
    void rangeUvDeduplicatesAcrossDaysWhileVersionsAndCaseSensitiveMappingsRemainSeparate() throws Exception {
        db.update("INSERT INTO short_link(short_code,original_url,created_at,expires_at,enabled) VALUES ('ab12','https://example.com',UTC_TIMESTAMP(3),'2026-09-01',true)");
        visit("Ab12", "2026-09-28 16:00:00", 1, 1);
        visit("Ab12", "2026-09-29 16:00:00", 1, 1);
        visit("Ab12", "2026-09-30 16:00:00", 1, 1);
        http.perform(stats("Ab12").param("from", "2026-09-29").param("to", "2026-10-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pv").value(3))
                .andExpect(jsonPath("$.uv").value(1))
                .andExpect(jsonPath("$.daily[0].uv").value(1))
                .andExpect(jsonPath("$.daily[1].uv").value(1))
                .andExpect(jsonPath("$.daily[2].uv").value(1));
        visit("Ab12", "2026-09-30 16:00:01", 2, 1);
        visit("ab12", "2026-09-30 16:00:02", 1, 1);
        http.perform(stats("Ab12")).andExpect(status().isOk())
                .andExpect(jsonPath("$.pv").value(4)).andExpect(jsonPath("$.uv").value(2))
                .andExpect(jsonPath("$.identityVersions[0]").value(1))
                .andExpect(jsonPath("$.identityVersions[1]").value(2));
        http.perform(stats("ab12")).andExpect(status().isOk())
                .andExpect(jsonPath("$.pv").value(1)).andExpect(jsonPath("$.uv").value(1));
        org.mockito.Mockito.verifyNoInteractions(mapper, cache);
    }

    @Test
    void inclusiveThirtyDayWindowUsesShanghaiUtcBoundariesWithoutDependingOnCleanup() throws Exception {
        visit("Ab12", "2026-09-01 15:59:59.999", 1, 1);
        visit("Ab12", "2026-09-01 16:00:00", 1, 2);
        visit("Ab12", "2026-10-01 15:59:59.999", 1, 3);
        visit("Ab12", "2026-10-01 16:00:00", 1, 4);
        http.perform(stats("Ab12").param("from", "2026-09-02").param("to", "2026-10-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pv").value(2))
                .andExpect(jsonPath("$.uv").value(2)).andExpect(jsonPath("$.daily.length()").value(30))
                .andExpect(jsonPath("$.daily[0].date").value("2026-09-02"))
                .andExpect(jsonPath("$.daily[0].pv").value(1))
                .andExpect(jsonPath("$.daily[29].pv").value(1));
        http.perform(stats("Ab12").param("from", "2026-10-01").param("to", "2026-10-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pv").value(1));
    }

    @Test
    void rejectsMalformedOrOutOfWindowDatesAndMissingOrIllegalCodes() throws Exception {
        for (String[] dates : new String[][]{
                {"2026-09-30", null}, {null, "2026-10-01"}, {"", "2026-10-01"},
                {"2026-9-30", "2026-10-01"}, {"2026-09-31", "2026-10-01"},
                {"2026-10-01", "2026-09-30"}, {"2026-10-01", "2026-10-02"},
                {"2026-09-01", "2026-10-01"}}) {
            var request = stats("Ab12");
            if (dates[0] != null) request.param("from", dates[0]);
            if (dates[1] != null) request.param("to", dates[1]);
            http.perform(request).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        for (String key : new String[]{"from", "to"}) {
            http.perform(stats("Ab12").param("from", "2026-09-30").param("to", "2026-10-01").param(key, "2026-09-30"))
                    .andExpect(status().isBadRequest());
        }
        for (String code : new String[]{"bad!", "ABC", "123456789", "None"}) {
            http.perform(stats(code)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        http.perform(stats("Ab12")).andExpect(status().isOk());
    }

    @Test
    void authenticationPrecedesValidationForGetAndHeadWithoutDatabaseAccess() throws Exception {
        org.mockito.Mockito.clearInvocations(pool, mapper, cache);
        for (var method : new org.springframework.http.HttpMethod[]{org.springframework.http.HttpMethod.GET, org.springframework.http.HttpMethod.HEAD}) {
            for (String[] token : new String[][]{ {}, {"bad"}, {"0123456789abcdef0123456789abcdef", "0123456789abcdef0123456789abcdef"}}) {
                var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(method, "/api/internal/links/bad!/stats").param("from", "bad");
                if (token.length > 0) request.header("X-Internal-Token", (Object[]) token);
                http.perform(request).andExpect(status().isUnauthorized())
                        .andExpect(header().string("Cache-Control", "no-store"));
            }
        }
        org.mockito.Mockito.verify(pool, org.mockito.Mockito.never()).getConnection();
        org.mockito.Mockito.verifyNoInteractions(mapper, cache);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head("/api/internal/links/Ab12/stats")
                .header("X-Internal-Token", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void realHttpHeadHasNoBodyAndReusesAuthenticationAndDateValidation() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        var uri = java.net.URI.create("http://127.0.0.1:" + port + "/api/internal/links/Ab12/stats");
        for (boolean authorized : new boolean[]{false, true}) {
            var request = java.net.http.HttpRequest.newBuilder(uri).method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody());
            if (authorized) request.header("X-Internal-Token", "0123456789abcdef0123456789abcdef");
            var response = client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            org.assertj.core.api.Assertions.assertThat(response.statusCode()).isEqualTo(authorized ? 200 : 401);
            org.assertj.core.api.Assertions.assertThat(response.body()).isEmpty();
            org.assertj.core.api.Assertions.assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        }
    }

    @Test
    void busyQueryReturnsImmediatelyAndReleasesCapacityAfterSuccess() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            org.assertj.core.api.Assertions.assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(pool).getConnection();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> http.perform(stats("Ab12")).andExpect(status().isOk()));
            org.assertj.core.api.Assertions.assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            http.perform(stats("Ab12")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("STATS_BUSY"))
                    .andExpect(header().string("Cache-Control", "no-store"));
            release.countDown();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { release.countDown(); executor.shutdownNow(); }
        org.mockito.Mockito.doCallRealMethod().when(pool).getConnection();
        http.perform(stats("Ab12")).andExpect(status().isOk());
    }

    @Test
    void databaseFailuresAreNeverZeroSuccessOrRetriedAndReleaseCapacity() throws Exception {
        for (var failure : new java.sql.SQLException[]{new java.sql.SQLTimeoutException("private timeout"),
                new java.sql.SQLException("private database", "42000"), new java.sql.SQLException("private network", "08S01")}) {
            org.mockito.Mockito.clearInvocations(pool);
            org.mockito.Mockito.doThrow(failure).when(pool).getConnection();
            boolean timeout = failure instanceof java.sql.SQLTimeoutException;
            http.perform(stats("Ab12")).andExpect(status().is(timeout ? 503 : 500))
                    .andExpect(jsonPath("$.code").value(timeout ? "STATS_QUERY_TIMEOUT" : "INTERNAL_ERROR"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
            org.mockito.Mockito.verify(pool).getConnection();
            org.mockito.Mockito.doCallRealMethod().when(pool).getConnection();
            http.perform(stats("Ab12")).andExpect(status().isOk());
        }
    }

    /** Controls only the JDBC boundary; all reads and mutations still execute against real MySQL. */
    private void controlReads(java.util.function.UnaryOperator<String> sql,
            java.util.function.BiConsumer<String, java.sql.Connection> afterRead) throws Exception {
        controlReads(sql, afterRead, null);
    }

    private void controlReads(java.util.function.UnaryOperator<String> sql,
            java.util.function.BiConsumer<String, java.sql.Connection> afterRead, javax.sql.DataSource source) throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            var real = source == null ? (java.sql.Connection) invocation.callRealMethod() : source.getConnection();
            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                    (proxy, method, args) -> {
                        if (!method.getName().equals("prepareStatement")) return invoke(real, method, args);
                        String original = (String) args[0];
                        args[0] = sql.apply(original);
                        var statement = (java.sql.PreparedStatement) invoke(real, method, args);
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.PreparedStatement.class},
                                (p, m, a) -> {
                                    Object result = invoke(statement, m, a);
                                    if (m.getName().equals("executeQuery")) afterRead.accept(original, real);
                                    return result;
                                });
                    });
        }).when(pool).getConnection();
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    }

    @Test
    void summaryTrendAndVersionsUseOneReadOnlySnapshotAcrossConcurrentInsertAndDelete() throws Exception {
        visit("Ab12", "2026-09-30 16:00:00", 1, 1);
        var summaryRead = new java.util.concurrent.CountDownLatch(1);
        var mutated = new java.util.concurrent.CountDownLatch(1);
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        controlReads(java.util.function.UnaryOperator.identity(), (sql, connection) -> {
            if (sql.startsWith("SELECT COUNT(*)") && once.compareAndSet(false, true)) {
                try {
                    org.assertj.core.api.Assertions.assertThat(connection.isReadOnly()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(connection.getAutoCommit()).isFalse();
                    org.assertj.core.api.Assertions.assertThat(connection.getTransactionIsolation()).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
                    summaryRead.countDown();
                    org.assertj.core.api.Assertions.assertThat(mutated.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                } catch (Exception failure) { throw new RuntimeException(failure); }
            }
        });
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var response = executor.submit(() -> http.perform(stats("Ab12"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.pv").value(1))
                    .andExpect(jsonPath("$.uv").value(1)).andExpect(jsonPath("$.daily[6].pv").value(1))
                    .andExpect(jsonPath("$.daily[6].uv").value(1))
                    .andExpect(jsonPath("$.identityVersions.length()").value(1))
                    .andExpect(jsonPath("$.identityVersions[0]").value(1)));
            org.assertj.core.api.Assertions.assertThat(summaryRead.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var mutation = executor.submit(() -> {
                // This update also proves the ordinary mapping read has taken no row lock.
                db.update("UPDATE short_link SET enabled=true WHERE short_code='Ab12'");
                db.update("DELETE FROM short_link_visit_log WHERE short_code='Ab12'");
                visit("Ab12", "2026-09-30 16:00:01", 2, 2);
                visit("Ab12", "2026-09-30 16:00:02", 2, 3);
            });
            mutation.get(3, java.util.concurrent.TimeUnit.SECONDS);
            mutated.countDown();
            response.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { mutated.countDown(); executor.shutdownNow(); }
        org.mockito.Mockito.doCallRealMethod().when(pool).getConnection();
        http.perform(stats("Ab12")).andExpect(status().isOk()).andExpect(jsonPath("$.pv").value(2))
                .andExpect(jsonPath("$.uv").value(2)).andExpect(jsonPath("$.identityVersions[0]").value(2));
    }

    @Test
    void realStatementTimeoutReturnsExplicitErrorAndSubsequentQueryRecovers() throws Exception {
        long timeoutsBefore = queryObservations.timeouts();
        controlReads(sql -> sql.startsWith("SELECT short_code") ? sql + " AND SLEEP(5)=0" : sql, (sql, connection) -> {});
        for (String endpoint : new String[]{"stats", "visits"}) {
        long start = System.nanoTime();
        http.perform(get("/api/internal/links/Ab12/" + endpoint)
                .header("X-Internal-Token", "0123456789abcdef0123456789abcdef")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STATS_QUERY_TIMEOUT"))
                .andExpect(header().string("Cache-Control", "no-store"));
        org.assertj.core.api.Assertions.assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
                .isBetween(java.time.Duration.ofMillis(700), java.time.Duration.ofSeconds(4));
        }
        org.assertj.core.api.Assertions.assertThat(queryObservations.timeouts()).isEqualTo(timeoutsBefore + 2);
        org.mockito.Mockito.doCallRealMethod().when(pool).getConnection();
        http.perform(stats("Ab12")).andExpect(status().isOk());
    }

    @Test
    void realSocketTimeoutIsRecognizedThroughConnectorCauseAndObserved() throws Exception {
        long timeoutsBefore = queryObservations.timeouts();
        try (var socketPool = new com.zaxxer.hikari.HikariDataSource()) {
            socketPool.setJdbcUrl(pool.getJdbcUrl());
            socketPool.setUsername(pool.getUsername());
            socketPool.setPassword(pool.getPassword());
            socketPool.setMaximumPoolSize(1);
            socketPool.setMinimumIdle(0);
            socketPool.addDataSourceProperty("socketTimeout", "300");
            socketPool.addDataSourceProperty("connectTimeout", "500");
            controlReads(sql -> sql.startsWith("SELECT short_code") ? sql + " AND SLEEP(5)=0" : sql,
                    (sql, connection) -> {}, socketPool);
            long start = System.nanoTime();
            try {
                http.perform(stats("Ab12")).andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("STATS_QUERY_TIMEOUT"))
                        .andExpect(header().string("Cache-Control", "no-store"));
                org.assertj.core.api.Assertions.assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
                        .isBetween(java.time.Duration.ofMillis(200), java.time.Duration.ofSeconds(2));
                org.assertj.core.api.Assertions.assertThat(queryObservations.timeouts()).isEqualTo(timeoutsBefore + 1);
            } finally { org.mockito.Mockito.doCallRealMethod().when(pool).getConnection(); }
        }
        http.perform(stats("Ab12")).andExpect(status().isOk());
    }

    @Test
    void exhaustedStatisticsPoolTimesOutWithoutUsingTheCorePoolAndRecovers() throws Exception {
        http.perform(stats("Ab12")).andExpect(status().isOk());
        var held = new java.util.ArrayList<java.sql.Connection>();
        try {
            for (int i = 0; i < 4; i++) held.add(pool.getConnection());
            http.perform(stats("Ab12")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("STATS_QUERY_TIMEOUT"));
        } finally { for (var connection : held) connection.close(); }
        http.perform(stats("Ab12")).andExpect(status().isOk());
    }
}
