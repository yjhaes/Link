package com.example.shortlink.api.management;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InternalManagementWebConfiguration implements WebMvcConfigurer {
    private final InternalManagementAccess access;

    public InternalManagementWebConfiguration(InternalManagementAccess access) {
        this.access = access;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(access).order(Integer.MIN_VALUE);
    }
}
