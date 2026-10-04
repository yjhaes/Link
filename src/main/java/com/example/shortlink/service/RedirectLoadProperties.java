package com.example.shortlink.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("short-link.redirect-load")
public record RedirectLoadProperties(@DefaultValue("4") int maxConcurrent) {
    public RedirectLoadProperties {
        if (maxConcurrent < 1 || maxConcurrent > 1000) {
            throw new IllegalArgumentException("Redirect database load concurrency must be between 1 and 1000.");
        }
    }
}
