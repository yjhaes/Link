package com.example.shortlink.stats.persistence;
import com.example.shortlink.stats.VisitEvent;
import com.example.shortlink.stats.VisitStatsProperties;


import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class VisitWriteObservationTest {
    private final VisitEvent event = new VisitEvent(UUID.randomUUID(), "Ab12", Instant.EPOCH,
            LocalDate.of(1970, 1, 1), new byte[32], 1, null, null, null);

    @Test
    void onlyTheEventUniqueConstraintIsAnOrdinaryDuplicate() throws Exception {
        for (var failure : new SQLException[]{
                new SQLException("Duplicate entry 'private' for key 'short_link_visit_log.uq_visit_event'", "23000", 1062),
                new SQLException("Duplicate entry 'private' for key 'another_key'", "23000", 1062),
                new SQLException("private", "42000", 1142),
                new java.sql.SQLTimeoutException("private"),
                new SQLException("private", "08S01"),
                new SQLException("private", "HY000", 1205)}) {
            var pool = mock(DataSource.class);
            var connection = mock(java.sql.Connection.class);
            var statement = mock(java.sql.PreparedStatement.class);
            when(pool.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeUpdate()).thenThrow(failure);
            var observations = new VisitWriteObservations(pool);
            var recorder = new MySqlVisitPersistence(pool,
                    new VisitStatsProperties(false, null, null, null, null, null, null, null, null), observations);
            if (failure.getMessage().contains("uq_visit_event")) {
                assertThat(recorder.persist(event)).isEqualTo(VisitPersistence.Outcome.DUPLICATE);
            } else {
                var kind = failure instanceof java.sql.SQLTimeoutException || "08S01".equals(failure.getSQLState())
                        ? VisitPersistenceException.Failure.UNCERTAIN : failure.getErrorCode() == 1205
                        ? VisitPersistenceException.Failure.TRANSIENT : VisitPersistenceException.Failure.PERMANENT;
                assertThatThrownBy(() -> recorder.persist(event)).isInstanceOf(VisitPersistenceException.class)
                        .extracting("failure").isEqualTo(kind);
            }
            var expected = failure.getMessage().contains("uq_visit_event") ? VisitWriteObservations.Outcome.DUPLICATE
                    : failure instanceof java.sql.SQLTimeoutException || "08S01".equals(failure.getSQLState())
                    ? VisitWriteObservations.Outcome.UNCERTAIN : VisitWriteObservations.Outcome.FAILED;
            assertThat(observations.snapshot().outcomes().get(expected)).isEqualTo(1);
            assertThat(observations.snapshot().inFlight()).isZero();
        }
    }

    @Test
    void cleanupFailureDoesNotEraseTheConfirmedSave() throws Exception {
        var pool = mock(DataSource.class);
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.PreparedStatement.class);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        doThrow(new SQLException("private", "08S01")).when(connection).close();
        var observations = new VisitWriteObservations(pool);
        var recorder = new MySqlVisitPersistence(pool,
                new VisitStatsProperties(false, null, null, null, null, null, null, null, null), observations);
        assertThat(recorder.persist(event)).isEqualTo(VisitPersistence.Outcome.SAVED);
        assertThat(observations.snapshot().outcomes().get(VisitWriteObservations.Outcome.SAVED)).isEqualTo(1);
        assertThat(observations.snapshot().outcomes().get(VisitWriteObservations.Outcome.UNCERTAIN)).isZero();
        assertThat(observations.snapshot().categories().get(VisitWriteObservations.Category.CLEANUP)).isEqualTo(1);
    }
    @Test
    void connectionFailureIsObservableAndTheNextVisitCanAcquireCapacity() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MySqlVisitPersistence.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            DataSource pool = mock(DataSource.class);
            when(pool.getConnection()).thenThrow(new SQLException("secret", "08001"));
            var observations = new VisitWriteObservations(pool);
            var recorder = new MySqlVisitPersistence(pool,
                    new VisitStatsProperties(false, null, null, null, null, null, null, null, null), observations);
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> recorder.persist(new VisitEvent(UUID.randomUUID(), "Ab12",
                        Instant.EPOCH, LocalDate.of(1970, 1, 1), new byte[32], 1, null, null, null)))
                        .isInstanceOf(VisitPersistenceException.class)
                        .extracting("failure").isEqualTo(VisitPersistenceException.Failure.TRANSIENT);
            }
            var snapshot = observations.snapshot();
            assertThat(snapshot.attempted()).isEqualTo(3);
            assertThat(snapshot.outcomes().get(VisitWriteObservations.Outcome.FAILED)).isEqualTo(3);
            assertThat(snapshot.categories().get(VisitWriteObservations.Category.CONNECTION)).isEqualTo(3);
            assertThat(snapshot.inFlight()).isZero();
            assertThat(snapshot.durationNanos()).isPositive();
            assertThat(logs.list).allSatisfy(log -> {
                assertThat(log.getFormattedMessage()).contains("category=CONNECTION", "phase=CONNECTION").doesNotContain("secret");
                assertThat(log.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
}
