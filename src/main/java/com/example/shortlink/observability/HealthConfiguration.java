package com.example.shortlink.observability;

import com.example.shortlink.stats.config.VisitStatsProperties;
import com.example.shortlink.stats.messaging.AsyncVisitRecorder;
import com.example.shortlink.stats.messaging.VisitRabbitProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicReference;

/** Health reveals fixed categories only; optional dependencies never enter core readiness. */
@Configuration(proxyBeanMethods = false)
public class HealthConfiguration {
    @Bean
    HealthIndicator coreDatabase(@Qualifier("dataSource") DataSource core) {
        return () -> database(core);
    }

    @Bean
    HealthIndicator statisticsDatabase(@Qualifier("statsDataSource") DataSource statistics) {
        return () -> database(statistics);
    }

    private static Health database(DataSource source) {
        try (var connection = source.getConnection()) {
            boolean valid = connection.isValid(1);
            return (valid ? Health.up() : Health.down())
                    .withDetail("state", valid ? "available" : "unavailable").build();
        } catch (Exception unavailable) {
            // Never attach Throwable/SQL/connection metadata to health or its logs.
            return Health.down().withDetail("state", "unavailable").build();
        }
    }

    @Bean
    HealthIndicator redisDependency(RedisConnectionFactory redis) {
        return () -> {
            try (var connection = redis.getConnection()) {
                boolean valid = "PONG".equals(connection.ping());
                return (valid ? Health.up() : Health.down())
                        .withDetail("state", valid ? "available" : "unavailable").build();
            } catch (Exception unavailable) {
                return Health.down().withDetail("state", "unavailable").build();
            }
        };
    }

    @Bean
    HealthIndicator mqDependency(
            @Qualifier("visitPublisherConnectionFactory") CachingConnectionFactory publisher,
            @Qualifier("visitConsumerConnectionFactory") CachingConnectionFactory consumer) {
        var publishing = new ConnectionObservation(publisher);
        var consuming = new ConnectionObservation(consumer);
        return () -> {
            boolean publishingOpen = publishing.isOpen();
            boolean consumingOpen = consuming.isOpen();
            return (publishingOpen || consumingOpen ? Health.up() : Health.down())
                    .withDetail("publisherConnection", publishingOpen ? "connected" : "unavailable")
                    .withDetail("consumerConnection", consumingOpen ? "connected" : "unavailable")
                    .build();
        };
    }

    @Bean
    HealthIndicator statistics(
            VisitStatsProperties collection,
            VisitRabbitProperties rabbit,
            AsyncVisitRecorder recorder,
            @Qualifier("visitListener") SimpleMessageListenerContainer listener) {
        return () -> {
            var snapshot = recorder.snapshot();
            boolean started = snapshot != null && snapshot.started();
            boolean accepting = snapshot != null && snapshot.accepting();
            boolean running = listener.isRunning();
            int active = listener.getActiveConsumerCount();
            boolean recovering = snapshot != null && snapshot.recovering();
            return Health.up()
                    .withDetail("collectionIntent", collection.enabled() ? "enabled" : "disabled")
                    .withDetail("collectionActual", !collection.enabled() ? "disabled" :
                            started && accepting ? "active" : started ? "stopped" : "not-started")
                    .withDetail("handoffActual", !started ? "not-started" : accepting ? "accepting" : "stopped")
                    .withDetail("publisherActual", recovering ? "recovering" : "not-recovering")
                    .withDetail("consumerIntent", rabbit.consumerEnabled() ? "enabled" : "disabled")
                    .withDetail("consumerActual", running && active > 0 ? "running" :
                            running ? "starting-or-recovering" : "stopped")
                    .build();
        };
    }

    /** Passive native connection callbacks. Probes cannot connect or change lifecycle intent. */
    private static final class ConnectionObservation implements ConnectionListener {
        private final AtomicReference<Connection> current = new AtomicReference<>();
        ConnectionObservation(CachingConnectionFactory factory) {
            factory.addConnectionListener(this);
        }
        @Override public void onCreate(Connection connection) { current.set(connection); }
        @Override public void onClose(Connection connection) { current.compareAndSet(connection, null); }
        boolean isOpen() {
            var connection = current.get();
            return connection != null && connection.isOpen();
        }
    }
}
