package com.example.shortlink.stats.retention;
import com.example.shortlink.stats.VisitStatsProperties;



import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VisitLogCleanupTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-30T16:00:00Z"), ZoneOffset.UTC);
    private final VisitStatsProperties properties = new VisitStatsProperties(false, null, null, null, null, null, null, null, null);

    @Test
    void budgetStopsBetweenCommittedBatchesAndLeavesObservableBacklog() throws Exception {
        DataSource pool = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement count = mock(PreparedStatement.class), delete = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(startsWith("SELECT"))).thenReturn(count);
        when(connection.prepareStatement(startsWith("DELETE"))).thenReturn(delete);
        when(count.executeQuery()).thenReturn(rows);
        when(rows.getLong(1)).thenReturn(1001L);
        when(rows.getObject(2, LocalDate.class)).thenReturn(LocalDate.parse("2026-09-01"));
        AtomicLong elapsed = new AtomicLong();
        when(delete.executeUpdate()).thenAnswer(invocation -> { elapsed.set(Duration.ofSeconds(30).toNanos()); return 1000; });
        VisitLogCleanup cleanup = new VisitLogCleanup(pool, properties, clock, elapsed::get);
        cleanup.runRound();
        assertThat(cleanup.snapshot().outcome()).isEqualTo(VisitLogCleanup.Outcome.BUDGET);
        assertThat(cleanup.snapshot().deletedRows()).isEqualTo(1000);
        assertThat(cleanup.snapshot().expiredRows()).isEqualTo(1001);
        assertThat(cleanup.snapshot().backlogLowerBound()).isTrue();
        assertThat(cleanup.snapshot().oldestDate()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(cleanup.needsCatchUp()).isTrue();
        verify(connection).setAutoCommit(true);
        verify(delete).setObject(1, LocalDate.parse("2026-09-02"));
        verify(delete).executeUpdate();
        verify(delete).setQueryTimeout(1);
    }

    @Test
    void noCapacityDoesNotTouchDatabaseAndFailureReleasesPermitForNextRound() throws Exception {
        DataSource pool = mock(DataSource.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(pool.getConnection()).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new SQLException();
            throw new SQLException();
        });
        VisitLogCleanup cleanup = new VisitLogCleanup(pool, properties, clock);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> round = executor.submit(cleanup::runRound);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            cleanup.runRound();
            assertThat(cleanup.snapshot().outcome()).isEqualTo(VisitLogCleanup.Outcome.BUSY);
            verify(pool).getConnection();
            release.countDown();
            round.get(5, TimeUnit.SECONDS);
            assertThat(cleanup.snapshot().outcome()).isEqualTo(VisitLogCleanup.Outcome.FAILED);
            cleanup.runRound();
            verify(pool, times(2)).getConnection();
            assertThat(cleanup.needsCatchUp()).isTrue();
            assertThat(cleanup.snapshot().expiredRows()).isNull();
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
