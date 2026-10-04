package com.example.shortlink;

import com.example.shortlink.stats.config.VisitStatsProperties;
import com.example.shortlink.stats.messaging.AsyncVisitRecorder;
import com.example.shortlink.stats.messaging.VisitRabbitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Actual dual HTTP servers; controlled JDBC/Redis/AMQP boundaries, no personal facilities. */
class HealthManagementHttpTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class, RabbitAutoConfiguration.class},
        excludeName = "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
    @ComponentScan("com.example.shortlink.observability")
    @EnableConfigurationProperties({VisitStatsProperties.class, VisitRabbitProperties.class})
    static class Application {
        @Bean DataSource dataSource() throws Exception { return healthyDatabase(); }
        @Bean DataSource statsDataSource() throws Exception { return healthyDatabase(); }
        static DataSource healthyDatabase() throws Exception {
            var source = mock(DataSource.class);
            var connection = mock(Connection.class);
            when(connection.isValid(1)).thenReturn(true);
            when(source.getConnection()).thenReturn(connection);
            return source;
        }
        @Bean RedisConnectionFactory redisConnectionFactory() {
            var factory = mock(RedisConnectionFactory.class);
            var connection = mock(RedisConnection.class);
            when(connection.ping()).thenReturn("PONG");
            when(factory.getConnection()).thenReturn(connection);
            return factory;
        }
        @Bean CachingConnectionFactory visitPublisherConnectionFactory() { return observedFactory(); }
        @Bean CachingConnectionFactory visitConsumerConnectionFactory() { return observedFactory(); }
        static CachingConnectionFactory observedFactory() {
            var factory = mock(CachingConnectionFactory.class);
            when(factory.getRabbitConnectionFactory()).thenReturn(new com.rabbitmq.client.ConnectionFactory());
            return factory;
        }
        @Bean SimpleMessageListenerContainer visitListener() { return mock(SimpleMessageListenerContainer.class); }
        @Bean AsyncVisitRecorder recorder() { return mock(AsyncVisitRecorder.class); }
    }
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void probesObserveCoreAndProtectTheMainPort() throws Exception {
        try (var context = new SpringApplicationBuilder(Application.class).run(
                "--server.port=0", "--management.server.port=0", "--spring.sql.init.mode=never",
                "--spring.main.banner-mode=off", "--short-link.stats.enabled=false")) {
            int main = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            int management = context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
            assertThat(get(main, "/livez").statusCode()).isEqualTo(200);
            assertThat(get(main, "/livez").body()).isEqualTo("{\"status\":\"UP\"}");
            assertThat(get(main, "/readyz").statusCode()).isEqualTo(200);
            assertThat(get(main, "/readyz").body()).isEqualTo("{\"status\":\"UP\"}");
            for (String path : new String[]{"/actuator/health", "/actuator/metrics", "/actuator/info", "/actuator/health/readiness"}) {
                assertThat(get(main, path).statusCode()).as(path).isEqualTo(404);
            }
            for (String path : new String[]{"/actuator/env", "/actuator/configprops", "/actuator/heapdump", "/actuator/loggers", "/actuator/beans", "/actuator"}) {
                assertThat(get(management, path).statusCode()).as(path).isEqualTo(404);
            }
            assertThat(get(management, "/actuator/metrics").statusCode()).isEqualTo(200);
            assertThat(get(management, "/actuator/info").statusCode()).isEqualTo(200);
            assertThat(get(management, "/actuator/info").body()).contains("build", "version").doesNotContain("time", "os", "java", "env");
            assertThat(get(management, "/actuator/health/readiness").statusCode()).isEqualTo(200);
            var stats = context.getBean("statsDataSource", DataSource.class);
            when(stats.getConnection()).thenThrow(new SQLException("sql password cookie visitor canary"));
            when(context.getBean(RedisConnectionFactory.class).getConnection()).thenThrow(new IllegalStateException("redis://private canary"));
            var dependencies = get(management, "/actuator/health/dependencies");
            assertThat(dependencies.statusCode()).isEqualTo(503);
            assertThat(dependencies.body()).doesNotContain("canary", "redis://", "password", "exception");
            assertThat(get(main, "/readyz").statusCode()).isEqualTo(200);
            assertThat(get(main, "/livez").statusCode()).isEqualTo(200);
            var listener = context.getBean(SimpleMessageListenerContainer.class);
            when(listener.isRunning()).thenReturn(true);
            when(listener.getActiveConsumerCount()).thenReturn(1);
            assertThat(get(management, "/actuator/health/dependencies").body())
                    .contains("\"consumerIntent\":\"enabled\"", "\"consumerActual\":\"running\"");
            // A manual stop is observed independently of the configured enabled intent.
            when(listener.isRunning()).thenReturn(false);
            when(listener.getActiveConsumerCount()).thenReturn(0);
            assertThat(get(management, "/actuator/health/dependencies").body())
                    .contains("\"consumerIntent\":\"enabled\"", "\"consumerActual\":\"stopped\"");
            for (int i = 0; i < 3; i++) get(management, "/actuator/health/dependencies");
            verify(listener, never()).start();
            verify(listener, never()).stop();
            for (String name : new String[]{"visitPublisherConnectionFactory", "visitConsumerConnectionFactory"})
                verify(context.getBean(name, CachingConnectionFactory.class), never()).createConnection();
            var core = context.getBean("dataSource", DataSource.class);
            when(core.getConnection()).thenThrow(new SQLException("jdbc:mysql://private secret SQL canary"));
            assertThat(get(main, "/readyz").statusCode()).isEqualTo(503);
            assertThat(get(main, "/readyz").body()).isEqualTo("{\"status\":\"DOWN\"}");
            assertThat(get(main, "/livez").statusCode()).isEqualTo(200);
            assertThat(get(management, "/actuator/health").body()).doesNotContain("canary", "jdbc:", "exception", "dataSource", "statsDataSource");
            doReturn(Application.healthyDatabase().getConnection()).when(core).getConnection();
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            assertThat(get(main, "/readyz").statusCode()).isEqualTo(503);
            assertThat(get(main, "/livez").statusCode()).isEqualTo(200);
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
            assertThat(get(main, "/readyz").statusCode()).isEqualTo(200);
        }
    }
}
