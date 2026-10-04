package com.example.shortlink.api.ratelimit;

import com.example.shortlink.ratelimit.RateLimiter;
import com.example.shortlink.service.error.LinkNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import java.io.IOException;
import java.util.Map;

/** Cheap format rejection precedes Redis; unavailable admission leaves redirect fallback intact. */
public class RedirectRateLimitInterceptor implements HandlerInterceptor {
    private final RateLimiter limiter;
    public RedirectRateLimitInterceptor(RateLimiter limiter) { this.limiter = limiter; }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) return true;
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String code = attribute instanceof Map<?, ?> variables ? (String) variables.get("code") : null;
        if (code == null || !code.matches("[A-Za-z0-9]{4,8}")) throw new LinkNotFoundException();
        var decision = limiter.admitRedirect(request.getRemoteAddr());
        request.setAttribute("shortlink.admission", decision == null ? "unavailable" : decision.status().name().toLowerCase(java.util.Locale.ROOT));
        if (decision == null || decision.status() != RateLimiter.Decision.Status.REJECTED) return true;
        response.setStatus(429);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Retry-After", Long.toString(1 + (decision.waitMillis() - 1) / 1000));
        response.setContentType("application/json");
        if (!"HEAD".equals(request.getMethod())) response.getWriter().write(
                "{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Please wait before requesting another redirect.\"}");
        return false;
    }
}
