package com.example.shortlink.stats;


import com.example.shortlink.service.error.LinkNotFoundException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

import javax.sql.DataSource;

@Component
public class MySqlVisitStatsQuery {
    private static final String RANGE =
            " FROM short_link_visit_log WHERE short_code=?"
                    + " AND stat_date>=? AND stat_date<=? AND occurred_at>=? AND occurred_at<?";
    private final DataSource pool;
    private final VisitStatsProperties properties;
    private final VisitQueryObservations observations;
    private final Semaphore queries = new Semaphore(1);

    public MySqlVisitStatsQuery(
            @Qualifier("statsDataSource") DataSource pool,
            VisitStatsProperties properties,
            VisitQueryObservations observations) {
        this.pool = pool;
        this.properties = properties;
        this.observations = observations;
    }

    public VisitStatsResult query(String code, StatsDateRange range) {
        return read(code, connection -> aggregate(connection, code, range));
    }

    public VisitPageResult page(String code, StatsDateRange range, int limit, VisitCursor cursor) {
        return read(
                code,
                connection -> {
                    String position =
                            cursor == null
                                    ? ""
                                    : " AND (occurred_at<? OR (occurred_at=? AND id<?))";
                    List<VisitPageResult.Row> items = new ArrayList<>();
                    try (var statement =
                            prepare(
                                    connection,
                                    "SELECT id,occurred_at,peer_ip_network,user_agent,referer_host"
                                            + RANGE
                                            + position
                                            + " ORDER BY occurred_at DESC,id DESC LIMIT ?",
                                    code,
                                    range)) {
                        int index = 6;
                        if (cursor != null) {
                            statement.setObject(index++, cursor.occurredAt());
                            statement.setObject(index++, cursor.occurredAt());
                            statement.setLong(index++, cursor.id());
                        }
                        statement.setInt(index, limit + 1);
                        try (var rows = statement.executeQuery()) {
                            while (rows.next())
                                items.add(
                                        new VisitPageResult.Row(
                                                rows.getLong(1),
                                                rows.getObject(2, java.time.LocalDateTime.class),
                                                rows.getString(3),
                                                rows.getString(4),
                                                rows.getString(5)));
                        }
                    }
                    boolean hasMore = items.size() > limit;
                    if (hasMore) items.remove(items.size() - 1);
                    VisitCursor next = null;
                    if (hasMore) {
                        var last = items.get(items.size() - 1);
                        next = new VisitCursor(last.occurredAt(), last.id());
                    }
                    return new VisitPageResult(List.copyOf(items), next, hasMore);
                });
    }

    @FunctionalInterface
    private interface Read<T> {
        T execute(Connection connection) throws SQLException;
    }

    private <T> T read(String code, Read<T> read) {
        if (!queries.tryAcquire()) throw new StatsQueryException(StatsQueryException.Reason.BUSY);
        try (Connection connection = pool.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                // Ordinary reads establish and reuse one consistent snapshot without locking
                // mappings.
                try (var statement =
                        connection.prepareStatement(
                                "SELECT short_code FROM short_link WHERE short_code=?")) {
                    statement.setQueryTimeout(properties.statementTimeoutSeconds());
                    statement.setString(1, code);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) throw new LinkNotFoundException();
                    }
                }
                T result = read.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        } catch (SQLException failure) {
            boolean timedOut = timeout(failure);
            if (timedOut) observations.timedOut();
            throw new StatsQueryException(
                    timedOut
                            ? StatsQueryException.Reason.TIMEOUT
                            : StatsQueryException.Reason.DATABASE);
        } finally {
            queries.release();
        }
    }

    private VisitStatsResult aggregate(Connection connection, String code, StatsDateRange range)
            throws SQLException {
        long pv;
        long uv;
        try (var statement =
                        prepare(
                                connection,
                                "SELECT COUNT(*), COUNT(DISTINCT visitor_key_version,visitor_hash)"
                                        + RANGE,
                                code,
                                range);
                var rows = statement.executeQuery()) {
            rows.next();
            pv = rows.getLong(1);
            uv = rows.getLong(2);
        }
        Map<LocalDate, VisitStatsResult.Day> days = new HashMap<>();
        try (var statement =
                        prepare(
                                connection,
                                "SELECT stat_date,COUNT(*),COUNT(DISTINCT"
                                        + " visitor_key_version,visitor_hash)"
                                        + RANGE
                                        + " GROUP BY stat_date",
                                code,
                                range);
                var rows = statement.executeQuery()) {
            while (rows.next()) {
                LocalDate date = rows.getObject(1, LocalDate.class);
                days.put(
                        date,
                        new VisitStatsResult.Day(
                                date,
                                rows.getLong(2),
                                rows.getLong(3),
                                date.equals(range.today())));
            }
        }
        List<Integer> versions = new ArrayList<>();
        try (var statement =
                        prepare(
                                connection,
                                "SELECT DISTINCT visitor_key_version"
                                        + RANGE
                                        + " ORDER BY visitor_key_version",
                                code,
                                range);
                var rows = statement.executeQuery()) {
            while (rows.next()) versions.add(rows.getInt(1));
        }
        var daily =
                range.from()
                        .datesUntil(range.to().plusDays(1))
                        .map(
                                date ->
                                        days.getOrDefault(
                                                date,
                                                new VisitStatsResult.Day(
                                                        date, 0, 0, date.equals(range.today()))))
                        .toList();
        return new VisitStatsResult(pv, uv, List.copyOf(versions), daily);
    }

    private PreparedStatement prepare(
            Connection connection, String sql, String code, StatsDateRange range)
            throws SQLException {
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
            try {
                statement.close();
            } catch (SQLException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private boolean timeout(SQLException failure) {
        for (SQLException error = failure; error != null; error = error.getNextException()) {
            // Connector/J wraps socket timeouts in an 08S01 CommunicationsException.
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Throwable cause = error;
                    cause != null && seen.add(cause);
                    cause = cause.getCause()) {
                if (cause instanceof java.net.SocketTimeoutException
                        || cause instanceof SQLTimeoutException) return true;
            }
            String state = error.getSQLState();
            if (error instanceof SQLTimeoutException
                    || error instanceof SQLTransientConnectionException
                    || "S1T00".equals(state)
                    || "HYT00".equals(state)
                    || "HYT01".equals(state)
                    || error.getErrorCode() == 1205
                    || error.getErrorCode() == 3024) return true;
        }
        return false;
    }
}
