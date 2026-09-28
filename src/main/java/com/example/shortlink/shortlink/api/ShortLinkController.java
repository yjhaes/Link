package com.example.shortlink.shortlink.api;

import com.example.shortlink.common.error.InvalidRequestException;
import com.example.shortlink.shortlink.service.ShortLink;
import com.example.shortlink.shortlink.service.ShortLinkService;
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

    private final ShortLinkService shortLinkService;
    private final String baseUrl;

    public ShortLinkController(
            ShortLinkService shortLinkService,
            @Value("${short-link.base-url}") String baseUrl) {
        this.shortLinkService = shortLinkService;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @PostMapping("/api/links")
    public ResponseEntity<CreateLinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
        if (request.validMinutes() != null) {
            throw new InvalidRequestException("validMinutes is not supported in this version.");
        }
        ShortLink shortLink = shortLinkService.createPermanent(request.originalUrl());
        String shortUrl = baseUrl + "/s/" + shortLink.shortCode();
        CreateLinkResponse response = new CreateLinkResponse(
                shortLink.shortCode(),
                shortUrl,
                null);

        return ResponseEntity.created(URI.create(shortUrl)).body(response);
    }

    @GetMapping("/s/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        ShortLink shortLink = shortLinkService.findByCode(code);
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, shortLink.originalUrl())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
