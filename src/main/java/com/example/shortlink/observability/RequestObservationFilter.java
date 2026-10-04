package com.example.shortlink.observability;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

/** Observe completed HTTP work without retaining request data or creating identity labels. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class RequestObservationFilter extends OncePerRequestFilter {
    private final MeterRegistry meters;
    private final ConcurrentHashMap<String, Long> lastLog = new ConcurrentHashMap<>();
    public RequestObservationFilter(MeterRegistry meters) { this.meters = meters; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String previous = MDC.get("requestId");
        String id = UUID.randomUUID().toString();
        MDC.put("requestId", id);
        response.setHeader("X-Request-ID", id);
        long began = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            try {
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String route = MetricPrivacyConfiguration.route(pattern == null ? "" : pattern.toString());
                String group = MetricPrivacyConfiguration.group(route);
                int status = completed ? response.getStatus() : 500;
                String result = MetricPrivacyConfiguration.result(status);
                Object admission = request.getAttribute("shortlink.admission");
                if (admission != null && !group.equals("other")) {
                    meters.counter("shortlink.rate.admission", "group", group, "result", admission.toString()).increment();
                    var category = com.example.shortlink.logging.SafeOperationalLog.Category.valueOf("RATE_" + group.toUpperCase(java.util.Locale.ROOT));
                    var logger = LoggerFactory.getLogger(RequestObservationFilter.class);
                    if (admission.equals("unavailable")) com.example.shortlink.logging.SafeOperationalLog.sampled(logger, category);
                    else com.example.shortlink.logging.SafeOperationalLog.recovered(logger, category);
                }
                long elapsed = System.nanoTime() - began;
                meters.timer("shortlink.http.requests", "route", route, "group", group, "result", result)
                        .record(elapsed, TimeUnit.NANOSECONDS);
                if (!group.equals("other") && status >= 400) {
                    // The key contains only four fixed groups and six fixed results.
                    String key = group + ":" + result;
                    lastLog.compute(key, (ignored, last) -> {
                        if (last == null || began - last >= TimeUnit.SECONDS.toNanos(30)) {
                            LoggerFactory.getLogger(RequestObservationFilter.class).warn(
                                "Request outcome: operation={} result={} durationMs={} requestId={}; sampleWindowSeconds=30",
                                group, result, TimeUnit.NANOSECONDS.toMillis(elapsed), id);
                            return began;
                        }
                        return last;
                    });
                }
            } finally {
                if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous);
            }
        }
    }
}
