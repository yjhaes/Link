package com.example.shortlink.api;

import com.example.shortlink.service.ShortLinkCreationService;
import com.example.shortlink.service.RedirectService;
import com.example.shortlink.service.CreatedShortLink;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class ShortLinkController {

    private final ShortLinkCreationService creationService;
    private final RedirectService redirectService;
    private final String baseUrl;

    public ShortLinkController(
            ShortLinkCreationService creationService,
            RedirectService redirectService,
            @Value("${short-link.base-url}") String baseUrl) {
        this.creationService = creationService;
        this.redirectService = redirectService;
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

    @GetMapping("/s/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String originalUrl = redirectService.findOriginalUrl(code);
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, originalUrl)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
