package com.example.shortlink.api.ratelimit;

import com.example.shortlink.ratelimit.RateLimiter;
import com.example.shortlink.ratelimit.RateLimitProperties;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties({RateLimitProperties.class, RedisProperties.class})
public class CreateRateLimitWebConfiguration implements WebMvcConfigurer {
    private final RateLimiter limiter;

    public CreateRateLimitWebConfiguration(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CreateRateLimitInterceptor(limiter))
                .addPathPatterns("/api/links");
        registry.addInterceptor(new RedirectRateLimitInterceptor(limiter)).addPathPatterns("/s/{code}");
    }
}
