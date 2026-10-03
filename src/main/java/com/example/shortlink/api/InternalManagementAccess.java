package com.example.shortlink.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

/** Authenticates marked handlers before MVC resolves arguments or reads the body. */
@Component
public class InternalManagementAccess implements HandlerInterceptor {
    private final byte[] token;
    private final ObjectMapper objectMapper;

    public InternalManagementAccess(
            @Value("${short-link.internal-token:}") String token, ObjectMapper objectMapper) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
        if (this.token.length > 0 && this.token.length < 32) {
            throw new IllegalArgumentException(
                    "Internal management token must contain at least 32 UTF-8 bytes.");
        }
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method)
                || !(method.hasMethodAnnotation(InternalManagement.class)
                        || AnnotatedElementUtils.hasAnnotation(
                                method.getBeanType(), InternalManagement.class))) {
            return true;
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (token.length == 0) {
            return reject(response, 404, "RESOURCE_NOT_FOUND", "Resource not found.");
        }
        List<String> headers = Collections.list(request.getHeaders("X-Internal-Token"));
        if (headers.size() != 1
                || !MessageDigest.isEqual(token, headers.get(0).getBytes(StandardCharsets.UTF_8))) {
            return reject(
                    response,
                    401,
                    "INTERNAL_UNAUTHORIZED",
                    "Internal management authorization required.");
        }
        return true;
    }

    private boolean reject(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiError(code, message));
        return false;
    }
}
