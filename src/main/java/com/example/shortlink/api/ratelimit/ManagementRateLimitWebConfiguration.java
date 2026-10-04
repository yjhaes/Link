package com.example.shortlink.api.ratelimit;

import com.example.shortlink.ratelimit.RateLimiter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ManagementRateLimitWebConfiguration implements WebMvcConfigurer {
    private final RateLimiter limiter;
    public ManagementRateLimitWebConfiguration(RateLimiter limiter) { this.limiter = limiter; }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ManagementRateLimitInterceptor(limiter))
                .order(Integer.MIN_VALUE + 1)
                .addPathPatterns("/api/links/*/enabled", "/api/internal/links/*/stats", "/api/internal/links/*/visits");
    }
}
