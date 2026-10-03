package com.example.shortlink.stats.retention;

import com.example.shortlink.stats.StatsDateRange;
import com.example.shortlink.stats.config.VisitStatsProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

import javax.sql.DataSource;

/** Independent, restartable maintenance: every delete is its own committed batch. */
@Component
public class VisitLogCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(VisitLogCleanup.class);
    private static final long BUDGET_NANOS = Duration.ofSeconds(30).toNanos();
    private final DataSource pool;
    private final VisitStatsProperties properties;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final Semaphore capacity = new Semaphore(1);
    private volatile Snapshot snapshot = new Snapshot(null, false, null, null, Outcome.NOT_RUN, 0);
    private volatile boolean catchUp = true;

    public enum Outcome {
        NOT_RUN,
        COMPLETE,
        BUDGET,
        BUSY,
        FAILED
    }

    /** Backlog fields are the last successful database observation, possibly stale. */
    public record Snapshot(
            Long expiredRows,
            boolean backlogLowerBound,
            LocalDate oldestDate,
            Instant observedAt,
            Outcome outcome,
            long deletedRows) {}

    @org.springframework.beans.factory.annotation.Autowired
    public VisitLogCleanup(
            @Qualifier("statsDataSource") DataSource pool,
            VisitStatsProperties properties,
            Clock clock) {
        this(pool, properties, clock, System::nanoTime);
    }

    VisitLogCleanup(
            DataSource pool, VisitStatsProperties properties, Clock clock, LongSupplier nanoTime) {
        this.pool = pool;
        this.properties = properties;
        this.clock = clock;
        this.nanoTime = nanoTime;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public boolean needsCatchUp() {
        return catchUp;
    }

    public void runRound() {
        if (!capacity.tryAcquire()) {
            catchUp = true;
            publish(Outcome.BUSY, 0);
            return;
        }
        long started = nanoTime.getAsLong();
        long deleted = 0;
        LocalDate cutoff =
                StatsDateRange.earliestRetainedDate(
                        LocalDate.now(clock.withZone(StatsDateRange.ZONE)));
        catchUp = true;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(true);
            if (!withinBudget(started)) {
                publish(Outcome.BUDGET, deleted);
                return;
            }
            observe(connection, cutoff);
            while (withinBudget(started)) {
                int batch;
                try (var statement =
                        connection.prepareStatement(
                                "DELETE FROM short_link_visit_log"
                                        + " WHERE stat_date<? ORDER BY stat_date,id LIMIT 1000")) {
                    statement.setQueryTimeout(properties.statementTimeoutSeconds());
                    statement.setObject(1, cutoff);
                    batch = statement.executeUpdate();
                }
                deleted += batch;
                if (batch == 0) {
                    if (withinBudget(started)) observe(connection, cutoff);
                    catchUp = snapshot.expiredRows() == null || snapshot.expiredRows() > 0;
                    publish(catchUp ? Outcome.BUDGET : Outcome.COMPLETE, deleted);
                    return;
                }
            }
            publish(Outcome.BUDGET, deleted);
        } catch (SQLException | RuntimeException failure) {
            catchUp = true;
            publish(Outcome.FAILED, deleted);
            // Driver text can contain connection details; expose only a controlled category.
            LOG.warn("Visit log cleanup failed; deferred to the next maintenance round");
        } finally {
            capacity.release();
        }
    }

    private boolean withinBudget(long started) {
        return nanoTime.getAsLong() - started < BUDGET_NANOS;
    }

    private void observe(Connection connection, LocalDate cutoff) throws SQLException {
        // Bound the observation too: a large backlog must not require a full count before deletion.
        try (var statement =
                connection.prepareStatement(
                        "SELECT COUNT(*),MIN(stat_date) FROM (SELECT stat_date FROM"
                                + " short_link_visit_log FORCE INDEX (idx_visit_cleanup) WHERE"
                                + " stat_date<? ORDER BY stat_date,id LIMIT 1001) expired")) {
            statement.setQueryTimeout(properties.statementTimeoutSeconds());
            statement.setObject(1, cutoff);
            try (var rows = statement.executeQuery()) {
                rows.next();
                long count = rows.getLong(1);
                snapshot =
                        new Snapshot(
                                count,
                                count == 1001,
                                rows.getObject(2, LocalDate.class),
                                clock.instant(),
                                snapshot.outcome(),
                                snapshot.deletedRows());
            }
        }
    }

    private void publish(Outcome outcome, long deleted) {
        Snapshot previous = snapshot;
        snapshot =
                new Snapshot(
                        previous.expiredRows(),
                        previous.backlogLowerBound(),
                        previous.oldestDate(),
                        previous.observedAt(),
                        outcome,
                        deleted);
    }
}
