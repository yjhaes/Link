package com.example.shortlink.api.ratelimit;

import com.example.shortlink.api.ShortLinkController;
import com.example.shortlink.api.stats.VisitStatsController;
import com.example.shortlink.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import java.io.IOException;

/** Runs after internal authorization and before argument resolution or business work. */
public class ManagementRateLimitInterceptor implements HandlerInterceptor {
    private final RateLimiter limiter;
    public ManagementRateLimitInterceptor(RateLimiter limiter) { this.limiter = limiter; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method)) return true;
        RateLimiter.Decision decision;
        if ("PUT".equals(request.getMethod())
                && ShortLinkController.class.isAssignableFrom(method.getBeanType())
                && "setEnabled".equals(method.getMethod().getName())) {
            decision = limiter.admitManagementWrite();
        } else if (("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && VisitStatsController.class.isAssignableFrom(method.getBeanType())
                && ("statistics".equals(method.getMethod().getName())
                    || "visits".equals(method.getMethod().getName()))) {
            decision = limiter.admitManagementQuery();
        } else return true;
        request.setAttribute("shortlink.admission", decision.status().name().toLowerCase(java.util.Locale.ROOT));
        if (decision.status() == RateLimiter.Decision.Status.ALLOWED) return true;
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        String body;
        if (decision.status() == RateLimiter.Decision.Status.REJECTED) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(1 + (decision.waitMillis() - 1) / 1000));
            body = "{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Please wait before another management operation.\"}";
        } else {
            response.setStatus(503);
            body = "{\"code\":\"RATE_LIMIT_UNAVAILABLE\",\"message\":\"Admission could not be confirmed; management operation has not started.\"}";
        }
        if (!"HEAD".equals(request.getMethod())) response.getWriter().write(body);
        return false;
    }
}
