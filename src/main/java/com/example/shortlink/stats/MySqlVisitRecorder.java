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
public class MySqlVisitRecorder implements VisitRecorder {
    private static final Logger LOG = LoggerFactory.getLogger(MySqlVisitRecorder.class);
    private final DataSource pool;
    private final VisitStatsProperties properties;
    private final Semaphore writes = new Semaphore(2);

    public MySqlVisitRecorder(@Qualifier("statsDataSource") DataSource pool, VisitStatsProperties properties) {
        this.pool = pool;
        this.properties = properties;
    }

    @Override
    public void record(VisitEvent event) {
        if (!writes.tryAcquire()) return;
        try (Connection connection = pool.getConnection()) {
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
                statement.executeUpdate();
            }
        } catch (SQLException failure) {
            if (!eventDuplicate(failure)) LOG.warn("Visit write unconfirmed: event={}, category=database", event.eventId());
        } finally {
            writes.release();
        }
    }

    private boolean eventDuplicate(SQLException failure) {
        return failure.getErrorCode() == 1062 && "23000".equals(failure.getSQLState())
                && failure.getMessage() != null
                && failure.getMessage().matches("(?s).*for key '(?:[^']*\\.)?uq_visit_event'.*");
    }
}
