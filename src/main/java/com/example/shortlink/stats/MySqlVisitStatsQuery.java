package com.example.shortlink.stats;

import com.example.shortlink.service.error.LinkNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Semaphore;

@Component
public class MySqlVisitStatsQuery {
    private static final String RANGE = " FROM short_link_visit_log WHERE short_code=?"
            + " AND stat_date>=? AND stat_date<=? AND occurred_at>=? AND occurred_at<?";
    private final DataSource pool;
    private final VisitStatsProperties properties;
    private final Clock clock;
    private final Semaphore queries = new Semaphore(1);

    public MySqlVisitStatsQuery(@Qualifier("statsDataSource") DataSource pool,
            VisitStatsProperties properties, Clock clock) {
        this.pool = pool;
        this.properties = properties;
        this.clock = clock;
    }

    public VisitStatsResponse query(String code, StatsDateRange range) {
        if (!queries.tryAcquire()) throw new StatsQueryException(StatsQueryException.Reason.BUSY);
        try (Connection connection = pool.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                // Ordinary reads establish and reuse one consistent snapshot without locking mappings.
                try (var statement = connection.prepareStatement("SELECT short_code FROM short_link WHERE short_code=?")) {
                    statement.setQueryTimeout(properties.statementTimeoutSeconds());
                    statement.setString(1, code);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) throw new LinkNotFoundException();
                    }
                }
                long pv;
                long uv;
                try (var statement = prepare(connection, "SELECT COUNT(*), COUNT(DISTINCT visitor_key_version,visitor_hash)" + RANGE, code, range);
                        var rows = statement.executeQuery()) {
                    rows.next();
                    pv = rows.getLong(1);
                    uv = rows.getLong(2);
                }
                Map<LocalDate, VisitStatsResponse.Day> days = new HashMap<>();
                try (var statement = prepare(connection,
                        "SELECT stat_date,COUNT(*),COUNT(DISTINCT visitor_key_version,visitor_hash)" + RANGE + " GROUP BY stat_date", code, range);
                        var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        LocalDate date = rows.getObject(1, LocalDate.class);
                        days.put(date, new VisitStatsResponse.Day(date, rows.getLong(2), rows.getLong(3), date.equals(range.today())));
                    }
                }
                List<Integer> versions = new ArrayList<>();
                try (var statement = prepare(connection,
                        "SELECT DISTINCT visitor_key_version" + RANGE + " ORDER BY visitor_key_version", code, range);
                        var rows = statement.executeQuery()) {
                    while (rows.next()) versions.add(rows.getInt(1));
                }
                var daily = range.from().datesUntil(range.to().plusDays(1))
                        .map(date -> days.getOrDefault(date, new VisitStatsResponse.Day(date, 0, 0, date.equals(range.today())))).toList();
                connection.commit();
                return new VisitStatsResponse(code, range.from(), range.to(), StatsDateRange.ZONE.getId(), pv, uv,
                        "anonymous-cookie", "best-effort", properties.enabled(), List.copyOf(versions), clock.instant(), daily);
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); }
                catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new StatsQueryException(timeout(failure) ? StatsQueryException.Reason.TIMEOUT : StatsQueryException.Reason.DATABASE);
        } finally {
            queries.release();
        }
    }

    private PreparedStatement prepare(Connection connection, String sql, String code, StatsDateRange range) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(properties.statementTimeoutSeconds());
            statement.setString(1, code);
            statement.setObject(2, range.from());
            statement.setObject(3, range.to());
            statement.setObject(4, range.startUtc());
            statement.setObject(5, range.endUtc());
            return statement;
        } catch (SQLException | RuntimeException failure) {
            try { statement.close(); }
            catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private boolean timeout(SQLException failure) {
        for (SQLException error = failure; error != null; error = error.getNextException()) {
            String state = error.getSQLState();
            if (error instanceof SQLTimeoutException || error instanceof SQLTransientConnectionException
                    || "S1T00".equals(state) || "HYT00".equals(state) || "HYT01".equals(state)
                    || error.getErrorCode() == 1205 || error.getErrorCode() == 3024) return true;
        }
        return false;
    }
}
