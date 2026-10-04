package com.example.shortlink;

import com.example.shortlink.api.ratelimit.CreateRateLimitWebConfiguration;
import com.example.shortlink.ratelimit.RateLimiter;
import com.example.shortlink.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class CreateRateLimitConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CreateRateLimitWebConfiguration.class)
            .withBean(RateLimiter.class, () -> peer -> RateLimiter.Decision.allowed());

    @Test
    void defaultConfigurationIsBoundAtStartup() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(RateLimitProperties.class);
            assertThat(properties.createCapacity()).isEqualTo(3);
            assertThat(properties.refillMillis()).isEqualTo(6000);
            assertThat(properties.redirectCapacity()).isEqualTo(60);
            assertThat(properties.redirectRefillMillis()).isEqualTo(100);
            assertThat(properties.managementWriteCapacity()).isEqualTo(5);
            assertThat(properties.managementWriteRefillMillis()).isEqualTo(1000);
            assertThat(properties.managementQueryCapacity()).isEqualTo(5);
            assertThat(properties.managementQueryRefillMillis()).isEqualTo(1000);
        });
    }

    @Test
    void invalidAndOverflowingConfigurationFailsAtStartup() {
        for (String property : new String[]{"create-capacity=", "create-capacity=0", "create-capacity=-1", "create-capacity=1000001",
                "create-refill-interval=0ms", "create-refill-interval=-1s", "create-refill-interval=", 
                "create-refill-interval=9223372036854775807d", "create-refill-interval=25h",
                "create-refill-interval=7d", "redirect-capacity=0", "redirect-refill-interval=", "management-write-capacity=-1", "management-query-refill-interval=0ms"}) {
            runner.withPropertyValues("short-link.rate-limit." + property)
                    .run(context -> assertThat(context).hasFailed());
        }
        runner.withPropertyValues("short-link.rate-limit.create-capacity=1000000",
                        "short-link.rate-limit.create-refill-interval=1s")
                .run(context -> assertThat(context).hasFailed());
    }
}
