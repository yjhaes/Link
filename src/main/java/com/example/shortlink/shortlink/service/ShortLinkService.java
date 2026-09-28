package com.example.shortlink.shortlink.service;

import com.example.shortlink.common.error.InvalidRequestException;
import com.example.shortlink.common.error.LinkDisabledException;
import com.example.shortlink.common.error.LinkExpiredException;
import com.example.shortlink.common.error.LinkNotFoundException;
import com.example.shortlink.shortlink.persistence.ShortLinkEntity;
import com.example.shortlink.shortlink.persistence.ShortLinkMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Service
public class ShortLinkService {

    private static final int MAX_VALID_MINUTES = 5_256_000;

    private final ShortLinkMapper shortLinkMapper;
    private final ShortCodeGenerator shortCodeGenerator;
    private final Clock clock;

    public ShortLinkService(
            ShortLinkMapper shortLinkMapper,
            ShortCodeGenerator shortCodeGenerator,
            Clock clock) {
        this.shortLinkMapper = shortLinkMapper;
        this.shortCodeGenerator = shortCodeGenerator;
        this.clock = clock;
    }

    public CreatedShortLink create(String originalUrl, Integer validMinutes) {
        if (originalUrl == null || originalUrl.isBlank()) {
            throw new InvalidRequestException("originalUrl is required.");
        }
        if (originalUrl.length() > 4096) {
            throw new InvalidRequestException("originalUrl exceeds 4096 characters.");
        }
        if (!originalUrl.equals(originalUrl.strip())) {
            throw new InvalidRequestException("originalUrl must not have leading or trailing whitespace.");
        }
        if (originalUrl.chars().anyMatch(character -> character > 0x7f)) {
            throw new InvalidRequestException("originalUrl must use ASCII URI characters.");
        }
        validateHttpUri(originalUrl);

        if (validMinutes != null && (validMinutes < 1 || validMinutes > MAX_VALID_MINUTES)) {
            throw new InvalidRequestException("validMinutes must be between 1 and 5256000.");
        }

        Instant createdAtInstant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime createdAt = LocalDateTime.ofInstant(createdAtInstant, ZoneOffset.UTC);
        Instant expiresAtInstant = validMinutes == null
                ? null
                : createdAtInstant.plus(validMinutes, ChronoUnit.MINUTES);
        ShortLinkEntity entity = new ShortLinkEntity();
        entity.setShortCode(shortCodeGenerator.generate());
        entity.setOriginalUrl(originalUrl);
        entity.setCreatedAt(createdAt);
        entity.setExpiresAt(expiresAtInstant == null
                ? null
                : LocalDateTime.ofInstant(expiresAtInstant, ZoneOffset.UTC));
        entity.setEnabled(true);
        shortLinkMapper.insert(entity);

        return new CreatedShortLink(entity.getShortCode(), expiresAtInstant);
    }

    public String findOriginalUrl(String code) {
        if (code == null || !code.matches("[a-z0-9]{8}")) {
            throw new LinkNotFoundException();
        }

        ShortLinkEntity entity = shortLinkMapper.selectById(code);
        if (entity == null) {
            throw new LinkNotFoundException();
        }
        if (entity.getExpiresAt() != null
                && !LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).isBefore(entity.getExpiresAt())) {
            throw new LinkExpiredException();
        }
        if (!entity.isEnabled()) {
            throw new LinkDisabledException();
        }
        return entity.getOriginalUrl();
    }

    private void validateHttpUri(String originalUrl) {
        try {
            URI uri = new URI(originalUrl);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getPort() == 0
                    || uri.getPort() > 65535) {
                throw new InvalidRequestException("originalUrl must be an absolute HTTP or HTTPS URI with a host.");
            }
        } catch (URISyntaxException exception) {
            throw new InvalidRequestException("originalUrl is not a valid URI.");
        }
    }
}
