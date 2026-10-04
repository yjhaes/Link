package com.example.shortlink.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** An allowlist also protects Boot's automatic HTTP observations, including arbitrary 404 paths. */
@Configuration(proxyBeanMethods=false)
public class MetricPrivacyConfiguration {
    static String route(String candidate) {
        return switch(candidate) {
            case "/api/links", "/s/{code}", "/api/links/{code}/enabled",
                 "/api/internal/links/{code}/stats", "/api/internal/links/{code}/visits" -> candidate;
            default -> "other";
        };
    }
    static String group(String route) {
        return switch(route) {
            case "/api/links" -> "create";
            case "/s/{code}" -> "redirect";
            case "/api/links/{code}/enabled" -> "management_write";
            case "/api/internal/links/{code}/stats", "/api/internal/links/{code}/visits" -> "management_query";
            default -> "other";
        };
    }
    static String result(int status) {
        if (status == 429) return "rejected";
        if (status == 503) return "unavailable";
        if (status >= 500) return "failed";
        if (status >= 400) return "invalid";
        if (status >= 300) return "redirect";
        return "success";
    }
    @Bean MeterFilter safeHttpTags() {
        return new MeterFilter() {
            @Override public Meter.Id map(Meter.Id id) {
                if (!id.getName().startsWith("http.server.requests")) return id;
                String route = route(id.getTag("uri") == null ? "" : id.getTag("uri"));
                int status = 0;
                try { status = Integer.parseInt(id.getTag("status")); }
                catch (NumberFormatException ignored) { /* An active observation has no completed status. */ }
                return id.replaceTags(Tags.of("route", route, "group", group(route),
                        "result", status == 0 ? "active" : result(status)));
            }
        };
    }
}
