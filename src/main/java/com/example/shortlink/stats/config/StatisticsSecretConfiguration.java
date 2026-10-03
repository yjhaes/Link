package com.example.shortlink.stats.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** Prevents accidental reuse of management credentials for visitor identity. */
@Configuration(proxyBeanMethods = false)
public class StatisticsSecretConfiguration {
    public StatisticsSecretConfiguration(VisitStatsProperties properties,
            @Value("${short-link.internal-token:}") String managementToken) {
        if (properties.enabled() && !managementToken.isEmpty()
                && managementToken.equals(properties.visitorKey())) {
            throw new IllegalArgumentException("Statistics visitor key must be independent of the management token.");
        }
    }
}
