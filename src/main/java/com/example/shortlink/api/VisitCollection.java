package com.example.shortlink.api;

import com.example.shortlink.service.RedirectDecision;
import com.example.shortlink.stats.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

@Component
public class VisitCollection {
    private static final Logger LOG = LoggerFactory.getLogger(VisitCollection.class);
    private final VisitStatsProperties properties;
    private final VisitRecorder recorder;
    private final VisitIdentity identity;
    private final boolean secure;

    public VisitCollection(VisitStatsProperties properties, VisitRecorder recorder,
            @Value("${short-link.base-url}") String baseUrl) {
        this.properties = properties;
        this.recorder = recorder;
        this.identity = properties.enabled() ? new VisitIdentity(properties.visitorKey(), properties.visitorKeyVersion()) : null;
        this.secure = "https".equalsIgnoreCase(java.net.URI.create(baseUrl).getScheme());
    }

    public String collect(String code, RedirectDecision decision, HttpServletRequest request) {
        if (!properties.enabled() || !"GET".equals(request.getMethod())) return null;
        String cookie = null;
        try {
            var values = new ArrayList<String>();
            for (String header : Collections.list(request.getHeaders("Cookie"))) {
                for (String pair : header.split(";", -1)) {
                    String trimmed = pair.trim();
                    int equals = trimmed.indexOf('=');
                    String name = equals < 0 ? trimmed : trimmed.substring(0, equals).trim();
                    if ("sl_visitor".equals(name)) values.add(equals < 0 ? "" : trimmed.substring(equals+1));
                }
            }
            var visitor = identity.identify(code, values);
            if (visitor.newCookie() != null) {
                cookie = ResponseCookie.from("sl_visitor", visitor.newCookie()).path("/s")
                        .maxAge(Duration.ofDays(30)).httpOnly(true).sameSite("Lax").secure(secure || request.isSecure()).build().toString();
            }
            var occurredAt = decision.decidedAt().truncatedTo(ChronoUnit.MILLIS);
            recorder.record(new VisitEvent(UUID.randomUUID(), code, occurredAt,
                    occurredAt.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(), visitor.hash(), properties.visitorKeyVersion(),
                    VisitMetadata.peerNetwork(request.getRemoteAddr()), VisitMetadata.userAgent(request.getHeader("User-Agent")),
                    VisitMetadata.refererHost(request.getHeader("Referer"))));
        } catch (Exception failure) {
            LOG.warn("Visit collection failed: category=collection");
        }
        return cookie;
    }
}
