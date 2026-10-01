package com.example.shortlink.api;

import com.example.shortlink.service.ShortLinkCreationService;
import com.example.shortlink.service.RedirectService;
import com.example.shortlink.service.CreatedShortLink;
import com.example.shortlink.service.ShortLinkStateService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class ShortLinkController {

    private final ShortLinkCreationService creationService;
    private final RedirectService redirectService;
    private final ShortLinkStateService stateService;
    private final String baseUrl;
    private final VisitCollection visits;

    public ShortLinkController(
            ShortLinkCreationService creationService,
            RedirectService redirectService,
            ShortLinkStateService stateService,
            VisitCollection visits,
            @Value("${short-link.base-url}") String baseUrl) {
        this.creationService = creationService;
        this.redirectService = redirectService;
        this.stateService = stateService;
        this.visits = visits;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @PostMapping("/api/links")
    public ResponseEntity<CreateLinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
        CreatedShortLink createdLink = creationService.create(request.originalUrl(), request.validMinutes());
        String shortUrl = baseUrl + "/s/" + createdLink.shortCode();
        CreateLinkResponse response = new CreateLinkResponse(
                createdLink.shortCode(),
                shortUrl,
                createdLink.expiresAt());

        return ResponseEntity.created(URI.create(shortUrl)).body(response);
    }

    @PutMapping("/api/links/{code}/enabled")
    @InternalManagement
    public ResponseEntity<EnabledStateResponse> setEnabled(@PathVariable String code,
            @Valid @RequestBody SetEnabledRequest request) {
        stateService.setEnabled(code, request.enabled());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new EnabledStateResponse(code, request.enabled()));
    }

    @GetMapping("/s/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code, jakarta.servlet.http.HttpServletRequest request) {
        var decision = redirectService.decide(code);
        var response = ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, decision.originalUrl())
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        String cookie = visits.collect(code, decision, request);
        if (cookie != null) response.header(HttpHeaders.SET_COOKIE, cookie);
        return response.build();
    }
}
