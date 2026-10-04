package com.example.shortlink;

import com.example.shortlink.service.RedirectLoadProperties;
import com.example.shortlink.configuration.CoreDataSourceConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

class RedirectResourceConfigurationTest {
    @Configuration
    @EnableConfigurationProperties({RedirectLoadProperties.class, DataSourceProperties.class})
    static class ConfigurationBoundary {}
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ConfigurationBoundary.class, CoreDataSourceConfiguration.class);
    @Test void defaultCorePoolAndActualLoadBudgetsBindIndependently() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RedirectLoadProperties.class).maxConcurrent()).isEqualTo(4);
            var core = context.getBean(HikariDataSource.class);
            assertThat(core.getMaximumPoolSize()).isEqualTo(8);
            assertThat(core.getConnectionTimeout()).isEqualTo(500);
        });
        runner.withPropertyValues("short-link.redirect-load.max-concurrent=2", "spring.datasource.hikari.maximum-pool-size=12",
                "spring.datasource.hikari.connection-timeout=750").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RedirectLoadProperties.class).maxConcurrent()).isEqualTo(2);
            assertThat(context.getBean(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(12);
            assertThat(context.getBean(HikariDataSource.class).getConnectionTimeout()).isEqualTo(750);
        });
    }
    @Test void invalidActualQueryBudgetsFailAtStartup() {
        for (String value : new String[]{"", "0", "-1", "1001"}) {
            runner.withPropertyValues("short-link.redirect-load.max-concurrent=" + value)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
