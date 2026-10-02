package com.example.shortlink.stats;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.*;
import java.time.ZoneOffset;
import java.util.concurrent.Semaphore;

@Component
public class MySqlVisitRecorder implements VisitRecorder, VisitPersistence {
    private static final Logger LOG = LoggerFactory.getLogger(MySqlVisitRecorder.class);
    private enum Phase { CONNECTION, PREPARE, EXECUTE, CLEANUP }
    private final DataSource pool;
    private final VisitStatsProperties properties;
    private final Semaphore writes = new Semaphore(2);
    private final VisitWriteObservations observations;

    public MySqlVisitRecorder(@Qualifier("statsDataSource") DataSource pool, VisitStatsProperties properties,
            VisitWriteObservations observations) {
        this.pool = pool;
        this.properties = properties;
        this.observations = observations;
    }

    @Override
    public void record(VisitEvent event) {
        try { persist(event); }
        catch (VisitPersistenceException ignored) { /* HTTP collection remains best effort. */ }
    }

    @Override
    public Outcome persist(VisitEvent event) {
        observations.attempted();
        long start = System.nanoTime();
        if (!writes.tryAcquire()) {
            observations.outcome(VisitWriteObservations.Outcome.DROPPED);
            observations.duration(System.nanoTime() - start);
            throw new VisitPersistenceException(VisitPersistenceException.Failure.BUSY);
        }
        observations.started();
        Phase phase = Phase.CONNECTION;
        Outcome result = null;
        try (Connection connection = pool.getConnection()) {
            phase = Phase.PREPARE;
            connection.setAutoCommit(true);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO short_link_visit_log
                    (event_id,short_code,occurred_at,stat_date,visitor_hash,visitor_key_version,peer_ip_network,user_agent,referer_host)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """)) {
                statement.setQueryTimeout(properties.statementTimeoutSeconds());
                statement.setBytes(1, ByteBuffer.allocate(16).putLong(event.eventId().getMostSignificantBits())
                        .putLong(event.eventId().getLeastSignificantBits()).array());
                statement.setString(2, event.shortCode());
                statement.setObject(3, java.time.LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
                statement.setObject(4, event.statDate());
                statement.setBytes(5, event.visitorHash());
                statement.setInt(6, event.visitorKeyVersion());
                statement.setString(7, event.peerIpNetwork());
                statement.setString(8, event.userAgent());
                statement.setString(9, event.refererHost());
                phase = Phase.EXECUTE;
                statement.executeUpdate();
                result = Outcome.SAVED;
                phase = Phase.CLEANUP;
                observations.outcome(VisitWriteObservations.Outcome.SAVED);
            }
        } catch (SQLException | RuntimeException failure) {
            if (phase == Phase.CLEANUP) {
                observations.category(VisitWriteObservations.Category.CLEANUP);
                LOG.warn("Visit write confirmed, cleanup failed: event={}, category=CLEANUP, phase={}", event.eventId(), phase);
            } else if (phase == Phase.EXECUTE && failure instanceof SQLException sql && eventDuplicate(sql)) {
                observations.outcome(VisitWriteObservations.Outcome.DUPLICATE);
                result = Outcome.DUPLICATE;
            } else {
                var category = phase == Phase.CONNECTION && failure instanceof SQLTransientConnectionException
                        ? VisitWriteObservations.Category.TIMEOUT : failure instanceof SQLException sql
                        ? category(sql) : VisitWriteObservations.Category.UNEXPECTED;
                // A lost reply, cancellation or unknown driver error cannot prove non-execution.
                boolean rejected = failure instanceof SQLException sql && explicitlyRejected(sql);
                observations.outcome(phase == Phase.EXECUTE && !rejected ? VisitWriteObservations.Outcome.UNCERTAIN
                        : VisitWriteObservations.Outcome.FAILED);
                observations.category(category);
                LOG.warn("Visit write unconfirmed: event={}, category={}, phase={}", event.eventId(), category, phase);
                var kind = phase == Phase.EXECUTE && !rejected
                        ? VisitPersistenceException.Failure.UNCERTAIN
                        : failure instanceof SQLException sql && transientFailure(sql)
                        ? VisitPersistenceException.Failure.TRANSIENT
                        : VisitPersistenceException.Failure.PERMANENT;
                throw new VisitPersistenceException(kind);
            }
        } finally {
            writes.release();
            observations.finished();
            observations.duration(System.nanoTime() - start);
        }
        return result;
    }

    private boolean explicitlyRejected(SQLException failure) {
        String state = failure.getSQLState();
        return (state != null && (state.startsWith("22") || state.startsWith("23") || state.startsWith("42")))
                || failure.getErrorCode() == 1205 || failure.getErrorCode() == 1213;
    }

    private boolean transientFailure(SQLException failure) {
        String state = failure.getSQLState();
        return failure instanceof SQLTransientException
                || (state != null && (state.startsWith("08") || state.startsWith("40")))
                || category(failure) == VisitWriteObservations.Category.TIMEOUT;
    }
    private VisitWriteObservations.Category category(SQLException failure) {
        String state = failure.getSQLState();
        if (failure instanceof SQLTimeoutException || "S1T00".equals(state) || "HYT00".equals(state)
                || "HYT01".equals(state) || failure.getErrorCode() == 1205 || failure.getErrorCode() == 3024)
            return VisitWriteObservations.Category.TIMEOUT;
        if (state != null && state.startsWith("08")) return VisitWriteObservations.Category.CONNECTION;
        if (state != null && state.startsWith("23")) return VisitWriteObservations.Category.CONSTRAINT;
        return VisitWriteObservations.Category.DATABASE;
    }

    private boolean eventDuplicate(SQLException failure) {
        return failure.getErrorCode() == 1062 && "23000".equals(failure.getSQLState())
                && failure.getMessage() != null
                && failure.getMessage().matches("(?s).*for key '(?:[^']*\\.)?uq_visit_event'.*");
    }
}
