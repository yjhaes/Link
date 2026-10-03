package com.example.shortlink;


import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.sql.SQLException;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

/** Real Connector/J + MySQL checks, each timeout isolated from competing shorter budgets. */
@SpringBootTest
@ActiveProfiles("test")
class VisitTimeoutTest {
    @Autowired @Qualifier("statsDataSource") HikariDataSource configuredPool;
    @Autowired javax.sql.DataSource corePool;

    /** Observe the real Socket.connect budget; do not substitute network behavior. */
    public static class ObservedSocketFactory extends com.mysql.cj.protocol.StandardSocketFactory {
        static final java.util.concurrent.atomic.AtomicInteger budget = new java.util.concurrent.atomic.AtomicInteger();
        @Override protected java.net.Socket createSocket(com.mysql.cj.conf.PropertySet properties) {
            return new java.net.Socket() {
                @Override public void connect(java.net.SocketAddress endpoint, int timeout) throws java.io.IOException {
                    budget.set(timeout);
                    super.connect(endpoint, timeout);
                }
            };
        }
    }

    @Test
    void connectorPassesConfiguredConnectTimeoutToTheRealTcpSocket() throws Exception {
        try (var pool = pool(10000, 10)) {
            ObservedSocketFactory.budget.set(0);
            pool.addDataSourceProperty("socketFactory", ObservedSocketFactory.class.getName());
            try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                statement.executeQuery("SELECT 1").close();
            }
            assertThat(ObservedSocketFactory.budget.get()).isEqualTo(500);
        }
    }

    @Test
    void stalledValidationUsesItsOwnBudgetThenReplacesTheConnection() throws Exception {
        var uri = java.net.URI.create(configuredPool.getJdbcUrl().substring(5));
        try (var proxy = new MysqlReplyProxy(uri.getHost(), uri.getPort() < 0 ? 3306 : uri.getPort());
                var pool = pool(10000, 10)) {
            pool.setJdbcUrl("jdbc:mysql://127.0.0.1:" + proxy.port() + uri.getRawPath() + "?sslMode=DISABLED");
            pool.setConnectionTimeout(3000);
            try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                statement.executeQuery("SELECT 1").close();
            }
            org.awaitility.Awaitility.await().pollDelay(Duration.ofMillis(650)).atMost(Duration.ofSeconds(5)).until(() -> true);
            proxy.discardFirstReplies();
            long start = System.nanoTime();
            try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                statement.executeQuery("SELECT 1").close();
            }
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(350), Duration.ofSeconds(3));
        }
    }

    private HikariDataSource pool(int socketMillis, int lockSeconds) {
        var pool = new HikariDataSource();
        pool.setJdbcUrl(configuredPool.getJdbcUrl());
        pool.setUsername(configuredPool.getUsername());
        pool.setPassword(configuredPool.getPassword());
        pool.setMaximumPoolSize(1);
        pool.setMinimumIdle(0);
        pool.setInitializationFailTimeout(-1);
        pool.setConnectionTimeout(1000);
        pool.setValidationTimeout(500);
        pool.addDataSourceProperty("connectTimeout", "500");
        pool.addDataSourceProperty("socketTimeout", Integer.toString(socketMillis));
        pool.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout=" + lockSeconds);
        return pool;
    }

    @Test
    void statementTimeoutCancelsRealMysqlSleepWithSocketBudgetHeldLonger() throws Exception {
        try (var pool = pool(10000, 10); var connection = pool.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            long start = System.nanoTime();
            assertThatThrownBy(() -> statement.executeQuery("SELECT SLEEP(8)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("Statement cancelled");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(700), Duration.ofSeconds(6));
        }
    }

    @Test
    void socketTimeoutBreaksRealMysqlReadWithoutStatementCancellation() throws Exception {
        try (var connection = configuredPool.getConnection(); var statement = connection.createStatement()) {
            assertThat(connection.getNetworkTimeout()).isEqualTo(1000);
            long start = System.nanoTime();
            assertThatThrownBy(() -> statement.executeQuery("SELECT SLEEP(8)"))
                    .isInstanceOf(SQLException.class).extracting(f -> ((SQLException) f).getSQLState()).isEqualTo("08S01");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(700), Duration.ofSeconds(6));
        }
    }

    @Test
    void sessionLockTimeoutIsIndependentOfStatementAndSocketTimeouts() throws Exception {
        try (var lock = corePool.getConnection(); var pool = pool(10000, 1)) {
            lock.setAutoCommit(false);
            try (var setup = lock.createStatement()) {
                setup.executeUpdate("INSERT INTO short_link(short_code,original_url,created_at,enabled) "
                        + "VALUES ('Lock03','https://example.com/',UTC_TIMESTAMP(3),true)");
            }
            try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                statement.setQueryTimeout(8);
                long start = System.nanoTime();
                assertThatThrownBy(() -> statement.executeUpdate("UPDATE short_link SET enabled=false WHERE short_code='Lock03'"))
                        .isInstanceOf(SQLException.class).extracting(f -> ((SQLException) f).getErrorCode()).isEqualTo(1205);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(700), Duration.ofSeconds(6));
            } finally { lock.rollback(); }
        }
    }

    @Test
    void killedIdleConnectionIsReplacedAndTheNextBorrowSucceeds() throws Exception {
        try (var pool = pool(1000, 1)) {
            long id;
            try (var connection = pool.getConnection(); var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                assertThat(result.next()).isTrue();
                id = result.getLong(1);
            }
            try (var core = corePool.getConnection(); var statement = core.createStatement()) { statement.execute("KILL " + id); }
            // Hikari deliberately skips validation for very recently used connections (500 ms).
            org.awaitility.Awaitility.await().pollDelay(Duration.ofMillis(650)).atMost(Duration.ofSeconds(5)).until(() -> true);
            try (var connection = pool.getConnection(); var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isNotEqualTo(id);
            }
        }
    }
}
