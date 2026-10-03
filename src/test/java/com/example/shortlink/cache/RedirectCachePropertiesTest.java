package com.example.shortlink.cache;



import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedirectCachePropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Settings.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RedirectCacheProperties.class)
    static class Settings { }

    @Test
    void defaultsPreserveTheExistingCacheBudgets() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            RedirectCacheProperties settings = context.getBean(RedirectCacheProperties.class);
            assertThat(settings.isEnabled()).isTrue();
            assertThat(settings.getTtl()).isEqualTo(Duration.ofMinutes(5));
            assertThat(settings.getNotFoundTtl()).isEqualTo(Duration.ofSeconds(30));
            assertThat(settings.getExpiredTtl()).isEqualTo(Duration.ofMinutes(5));
            assertThat(settings.getDisabledTtl()).isEqualTo(Duration.ofSeconds(15));
            assertThat(settings.getLoadWait()).isEqualTo(Duration.ofMillis(200));
        });
    }

    @Test
    void existingEnvironmentVariablesOverrideApplicationYaml() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("test-cache-environment", Map.of(
                        "SHORT_LINK_REDIRECT_CACHE_ENABLED", "false",
                        "SHORT_LINK_REDIRECT_CACHE_TTL", "7s",
                        "SHORT_LINK_NOT_FOUND_CACHE_TTL", "8s",
                        "SHORT_LINK_EXPIRED_CACHE_TTL", "9s",
                        "SHORT_LINK_DISABLED_CACHE_TTL", "10s",
                        "SHORT_LINK_REDIRECT_LOAD_WAIT", "11ms"))))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RedirectCacheProperties settings = context.getBean(RedirectCacheProperties.class);
                    assertThat(settings.isEnabled()).isFalse();
                    assertThat(settings.getTtl()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(settings.getNotFoundTtl()).isEqualTo(Duration.ofSeconds(8));
                    assertThat(settings.getExpiredTtl()).isEqualTo(Duration.ofSeconds(9));
                    assertThat(settings.getDisabledTtl()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(settings.getLoadWait()).isEqualTo(Duration.ofMillis(11));
                });
    }

    @Test
    void invalidDurationsFailStartupEvenWithCacheDisabled() {
        for (String key : new String[]{"ttl", "not-found-ttl", "expired-ttl", "disabled-ttl", "load-wait"}) {
            for (String value : new String[]{"0ms", "-1s", "nonsense", "", "PT9223372036854775807S"}) {
                runner.withPropertyValues("short-link.redirect-cache.enabled=false",
                        "short-link.redirect-cache." + key + "=" + value)
                        .run(context -> assertThat(context).hasFailed());
            }
        }
        runner.withPropertyValues("short-link.redirect-cache.ttl=PT0.000999999S")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("short-link.redirect-cache.load-wait=PT0.000000001S")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void nullDurationCannotBypassValidation() {
        assertThatThrownBy(() -> new RedirectCacheProperties(true, null, Duration.ofSeconds(30),
                Duration.ofMinutes(5), Duration.ofSeconds(15), Duration.ofMillis(200)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
