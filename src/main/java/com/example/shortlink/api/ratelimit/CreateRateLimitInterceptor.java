package com.example.shortlink.api.ratelimit;

import com.example.shortlink.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/** Runs before MVC resolves request bodies and invokes the creation use case. */
public class CreateRateLimitInterceptor implements HandlerInterceptor {
    private final RateLimiter limiter;

    public CreateRateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        var decision = limiter.admitCreate(request.getRemoteAddr());
        if (decision.status() == RateLimiter.Decision.Status.ALLOWED) {
            return true;
        }
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        if (decision.status() == RateLimiter.Decision.Status.REJECTED) {
            response.setStatus(429);
            response.setHeader(
                    "Retry-After", Long.toString(1 + (decision.waitMillis() - 1) / 1000));
            response.getWriter().write(
                    "{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Please wait before creating another short link.\"}");
        } else {
            response.setStatus(503);
            response.getWriter().write(
                    "{\"code\":\"RATE_LIMIT_UNAVAILABLE\",\"message\":\"Admission could not be confirmed; creation has not started.\"}");
        }
        return false;
    }
}
