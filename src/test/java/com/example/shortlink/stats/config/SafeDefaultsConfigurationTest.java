package com.example.shortlink.stats.config;

import com.example.shortlink.api.management.InternalManagementAccess;
import com.example.shortlink.stats.messaging.VisitRabbitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

class SafeDefaultsConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Properties.class, StatisticsSecretConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(InternalManagementAccess.class);

    @Test
    void enablingCollectionRequiresAValidIndependentKey() {
        String key = "A".repeat(43) + "=";
        context.withPropertyValues("short-link.stats.enabled=true").run(app -> assertThat(app).hasFailed());
        context.withPropertyValues("short-link.stats.enabled=true", "short-link.stats.visitor-key=" + key,
                "short-link.internal-token=" + key).run(app -> assertThat(app).hasFailed());
        context.withPropertyValues("short-link.stats.enabled=true", "short-link.stats.visitor-key=" + key,
                "short-link.internal-token=" + "b".repeat(32)).run(app -> {
                    assertThat(app).hasNotFailed();
                    assertThat(app.getBean(VisitStatsProperties.class).enabled()).isTrue();
                    assertThat(app.getBean(VisitRabbitProperties.class).consumerEnabled()).isTrue();
                });
    }

    @Test
    void configuredVisitorKeyIsNotExposedByDiagnosticText() {
        String key = "A".repeat(43) + "=";
        context.withPropertyValues("short-link.stats.visitor-key=" + key).run(app ->
                assertThat(app.getBean(VisitStatsProperties.class).toString()).doesNotContain(key));
    }

    @Test
    void programmaticConfigurationAlsoHasNoDefaultBrokerPassword() {
        new ApplicationContextRunner().withUserConfiguration(Properties.class).run(app -> {
            VisitRabbitProperties rabbit = app.getBean(VisitRabbitProperties.class);
            assertThat(rabbit.username()).isEqualTo("short_link");
            assertThat(rabbit.password()).isEmpty();
            assertThat(rabbit.virtualHost()).isEqualTo("short_link");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({VisitStatsProperties.class, VisitRabbitProperties.class})
    static class Properties {}

    @Test
    void packagedConfigurationClosesManagementAndCollectionButKeepsConsumerEnabled() {
        context.run(app -> {
            assertThat(app).hasNotFailed();
            assertThat(app.getEnvironment().getProperty("short-link.internal-token")).isEmpty();
            assertThat(app.getBean(VisitStatsProperties.class).enabled()).isFalse();
            assertThat(app.getBean(VisitStatsProperties.class).visitorKey()).isEmpty();
            assertThat(app.getBean(VisitRabbitProperties.class).consumerEnabled()).isTrue();
        });
    }
}
