package com.example.shortlink.stats;

import static org.mockito.Mockito.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import javax.sql.DataSource;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"short-link.internal-token=0123456789abcdef0123456789abcdef", "short-link.stats.enabled=false", "short-link.redirect-cache.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")

class VisitCleanupLifecycleTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @MockitoBean Clock clock;
    @Autowired @Qualifier("statsDataSource") DataSource pool;
    @Autowired VisitStatsProperties properties;
    @MockitoBean VisitCleanupSchedule schedule;

    @BeforeEach
    void reset() {
        when(clock.instant()).thenReturn(Instant.parse("2026-09-30T16:00:00Z"));
        when(clock.withZone(any())).thenAnswer(call -> Clock.fixed(clock.instant(), call.getArgument(0)));
        db.update("DELETE FROM short_link_visit_log");
        db.update("DELETE FROM short_link");
        db.update("INSERT INTO short_link(short_code,original_url,created_at,enabled) VALUES ('Ab12','https://example.com',UTC_TIMESTAMP(3),true)");
    }

    private void visits(String day, int count) {
        for (int i = 0; i < count; i++) db.update("""
                INSERT INTO short_link_visit_log(event_id,short_code,occurred_at,stat_date,visitor_hash,visitor_key_version)
                VALUES(UUID_TO_BIN(UUID()),'Ab12',DATE_SUB(?,INTERVAL 8 HOUR),?,UNHEX(REPEAT('01',32)),1)
                """, day, day);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder stats(String from) {
        return get("/api/internal/links/Ab12/stats").header("X-Internal-Token", "0123456789abcdef0123456789abcdef")
                .param("from", from).param("to", "2026-10-01");
    }

    @Test
    void disabledCollectionRetainsProtectedHistoryAndRestartCatchesUpIndependentlyCommittedBatches() throws Exception {
        visits("2026-09-01", 2001);
        visits("2026-09-02", 1);
        visits("2026-10-01", 1);
        long issuance = db.queryForObject("SELECT COUNT(*) FROM short_code_issuance", Long.class);
        http.perform(stats("2026-09-01")).andExpect(status().isBadRequest());
        http.perform(stats("2026-09-02")).andExpect(status().isOk()).andExpect(jsonPath("$.pv").value(2))
                .andExpect(jsonPath("$.collectionEnabled").value(false));
        http.perform(get("/s/Ab12")).andExpect(status().isFound()).andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Long.class)).isEqualTo(2003);
        // Expire the round budget after the first DELETE has returned. A second connection observes its commit.
        AtomicLong calls = new AtomicLong();
        VisitLogCleanup first = new VisitLogCleanup(pool, properties, clock,
                () -> calls.incrementAndGet() <= 3 ? 0 : Duration.ofSeconds(30).toNanos());
        first.runRound();
        assertThat(first.snapshot().deletedRows()).isEqualTo(1000);
        assertThat(first.snapshot().outcome()).isEqualTo(VisitLogCleanup.Outcome.BUDGET);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log WHERE stat_date='2026-09-01'", Long.class)).isEqualTo(1001);
        VisitLogCleanup restarted = new VisitLogCleanup(pool, properties, clock);
        restarted.runRound();
        assertThat(restarted.snapshot().deletedRows()).isEqualTo(1001);
        assertThat(restarted.snapshot().expiredRows()).isZero();
        assertThat(restarted.snapshot().oldestDate()).isNull();
        assertThat(restarted.needsCatchUp()).isFalse();
        http.perform(stats("2026-09-02")).andExpect(status().isOk()).andExpect(jsonPath("$.pv").value(2));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link", Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM short_code_issuance", Long.class)).isEqualTo(issuance);
        // Process was stopped while the window advanced; a fresh instance needs no saved cursor.
        when(clock.instant()).thenReturn(Instant.parse("2026-10-01T16:00:00Z"));
        new VisitLogCleanup(pool, properties, clock).runRound();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Long.class)).isEqualTo(1);
    }

    @Test
    void realLockFailureLeavesBacklogAndLaterRoundRecovers() throws Exception {
        visits("2026-09-01", 1);
        VisitLogCleanup cleanup = new VisitLogCleanup(pool, properties, clock);
        try (var lock = pool.getConnection()) {
            lock.setAutoCommit(false);
            try (var sql = lock.createStatement()) { sql.executeQuery("SELECT * FROM short_link_visit_log FOR UPDATE").close(); }
            cleanup.runRound();
            assertThat(cleanup.snapshot().outcome()).isEqualTo(VisitLogCleanup.Outcome.FAILED);
            assertThat(cleanup.snapshot().expiredRows()).isEqualTo(1);
            assertThat(cleanup.snapshot().oldestDate()).isEqualTo(LocalDate.parse("2026-09-01"));
            assertThat(cleanup.needsCatchUp()).isTrue();
            lock.rollback();
        }
        cleanup.runRound();
        assertThat(cleanup.snapshot().outcome()).isEqualTo(VisitLogCleanup.Outcome.COMPLETE);
        assertThat(cleanup.snapshot().expiredRows()).isZero();
    }

    @Test
    void concurrentInstancesDeleteOnlyExpiredRowsWithoutDistributedCoordination() throws Exception {
        visits("2026-09-01", 1100);
        visits("2026-09-02", 1);
        var one = new VisitLogCleanup(pool, properties, clock);
        var two = new VisitLogCleanup(pool, properties, clock);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { start.await(); one.runRound(); return null; });
            var b = executor.submit(() -> { start.await(); two.runRound(); return null; });
            start.countDown();
            a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
            // A lock timeout/deadlock is allowed to defer one instance's round.
            one.runRound(); two.runRound();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM short_link_visit_log", Long.class)).isEqualTo(1);
            assertThat(one.snapshot().expiredRows()).isZero();
            assertThat(two.snapshot().expiredRows()).isZero();
        } finally { executor.shutdownNow(); }
        var plan = db.queryForList("EXPLAIN DELETE FROM short_link_visit_log WHERE stat_date<'2026-09-02' ORDER BY stat_date,id LIMIT 1000");
        assertThat(plan.get(0).get("key")).isEqualTo("idx_visit_cleanup");
    }
}
