package com.example.shortlink.api;



import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class InternalManagementConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(InternalManagementAccess.class);

    @Test
    void missingAndExplicitEmptyConfigurationAreAllowedAndCloseManagement() {
        context.run(application -> assertThat(application).hasNotFailed());
        context.withPropertyValues("short-link.internal-token=")
                .run(application -> assertThat(application).hasNotFailed());
    }

    @Test
    void shortSecretsFailStartupWithoutExposingTheirValue() {
        for (String value : new String[]{"sensitive-short-secret", "x".repeat(31)}) {
            context.withPropertyValues("short-link.internal-token=" + value).run(application -> {
                assertThat(application).hasFailed();
                assertThat(application.getStartupFailure()).hasRootCauseMessage(
                        "Internal management token must contain at least 32 UTF-8 bytes.");
                for (Throwable failure = application.getStartupFailure(); failure != null; failure = failure.getCause()) {
                    assertThat(failure.getMessage()).doesNotContain(value);
                }
            });
        }
    }

    @Test
    void minimumLengthSecretIsAccepted() {
        context.withPropertyValues("short-link.internal-token=" + "x".repeat(32))
                .run(application -> assertThat(application).hasNotFailed());
    }
}
