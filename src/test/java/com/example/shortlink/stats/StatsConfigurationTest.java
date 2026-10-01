package com.example.shortlink.stats;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

class StatsConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class))
            .withUserConfiguration(StatsDataSourceConfiguration.class)
            .withPropertyValues("spring.datasource.url=jdbc:mysql://127.0.0.1:1/unreachable");

    @Test
    void lazyStatisticsPoolDoesNotConnectAtStartupOrTakeOverCoreInfrastructure() {
        context.run(app -> {
            assertThat(app).hasNotFailed();
            var core = app.getBean("dataSource", DataSource.class);
            var stats = app.getBean("statsDataSource", HikariDataSource.class);
            assertThat(app.getBean(JdbcTemplate.class).getDataSource()).isSameAs(core);
            assertThat(app.getBean(JdbcTransactionManager.class).getDataSource()).isSameAs(core);
            assertThat(stats).isNotSameAs(core);
            assertThat(stats.getMaximumPoolSize()).isEqualTo(4);
            assertThat(stats.getMinimumIdle()).isZero();
            assertThat(stats.getInitializationFailTimeout()).isEqualTo(-1);
            assertThat(stats.getHikariPoolMXBean()).isNull();
            assertThat(app.getBean(VisitStatsProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void explicitInvalidConfigurationFailsEvenWhenCollectionIsDisabled() {
        for (String invalid : new String[]{"short-link.stats.visitor-key=bad",
                "short-link.stats.visitor-key-version=0", "short-link.stats.connection-timeout-ms=200",
                "short-link.stats.validation-timeout-ms=1000", "short-link.stats.connect-timeout-ms=0",
                "short-link.stats.socket-timeout-ms=0", "short-link.stats.statement-timeout-seconds=0",
                "short-link.stats.lock-timeout-seconds=0", "short-link.stats.enabled=true"}) {
            context.withPropertyValues(invalid).run(app -> assertThat(app).hasFailed());
        }
    }
}
