package com.example.shortlink.service;

import com.example.shortlink.cache.RedirectCache;
import com.example.shortlink.persistence.MySqlShortLinkWriter;
import com.example.shortlink.persistence.ShortCodeCollisionException;
import com.example.shortlink.persistence.ShortLinkEntity;
import com.example.shortlink.persistence.ShortLinkMapper;
import com.example.shortlink.service.error.CreateCacheCoordinationException;
import com.example.shortlink.service.error.InvalidRequestException;
import com.example.shortlink.service.error.LinkNotFoundException;
import com.example.shortlink.service.error.ShortCodeGenerationException;
import com.example.shortlink.shortcode.PermutedShortCodeEncoder;
import com.example.shortlink.shortcode.ShortCodeIdIssuer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Service
public class ShortLinkCreationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShortLinkCreationService.class);
    private static final int MAX_VALID_MINUTES = 5_256_000;
    private static final int MAX_SHORT_CODE_INSERT_ATTEMPTS = 2;
    private final ShortLinkMapper shortLinkMapper;
    private final MySqlShortLinkWriter mappingWriter;
    private final ShortCodeIdIssuer shortCodeIdIssuer;
    private final PermutedShortCodeEncoder shortCodeEncoder;
    private final Clock clock;
    private final RedirectCache redirectCache;

    public ShortLinkCreationService(
            ShortLinkMapper shortLinkMapper,
            MySqlShortLinkWriter mappingWriter,
            ShortCodeIdIssuer shortCodeIdIssuer,
            PermutedShortCodeEncoder shortCodeEncoder,
            Clock clock,
            RedirectCache redirectCache) {
        this.shortLinkMapper = shortLinkMapper;
        this.mappingWriter = mappingWriter;
        this.shortCodeIdIssuer = shortCodeIdIssuer;
        this.shortCodeEncoder = shortCodeEncoder;
        this.clock = clock;
        this.redirectCache = redirectCache;
    }

    // MyBatis commits each non-transactional insert before returning. Suspend any caller
    // transaction
    // so cache coordination never reports completion for an uncommitted mapping.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CreatedShortLink create(String originalUrl, Integer validMinutes) {
        if (originalUrl == null || originalUrl.isBlank()) {
            throw new InvalidRequestException("originalUrl is required.");
        }
        if (originalUrl.length() > 4096) {
            throw new InvalidRequestException("originalUrl exceeds 4096 characters.");
        }
        if (!originalUrl.equals(originalUrl.strip())) {
            throw new InvalidRequestException(
                    "originalUrl must not have leading or trailing whitespace.");
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
        Instant expiresAtInstant =
                validMinutes == null
                        ? null
                        : createdAtInstant.plus(validMinutes, ChronoUnit.MINUTES);
        ShortLinkEntity entity = new ShortLinkEntity();
        entity.setOriginalUrl(originalUrl);
        entity.setCreatedAt(createdAt);
        entity.setExpiresAt(
                expiresAtInstant == null
                        ? null
                        : LocalDateTime.ofInstant(expiresAtInstant, ZoneOffset.UTC));
        entity.setEnabled(true);
        insertWithCollisionRetries(entity);
        try {
            redirectCache.replaceVersion(entity.getShortCode());
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Coordination unconfirmed: operation=create category=committed-cache-unconfirmed shortCode={}",
                    entity.getShortCode());
            throw new CreateCacheCoordinationException(entity.getShortCode(), exception);
        }

        return new CreatedShortLink(entity.getShortCode(), expiresAtInstant);
    }

    /**
     * Internal maintenance entry point: call only after the mapping's database commit is confirmed.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recoverCacheCoordination(String shortCode) {
        if (shortCode == null
                || !shortCode.matches("[A-Za-z0-9]{4,8}")
                || shortLinkMapper.selectById(shortCode) == null) {
            throw new LinkNotFoundException();
        }
        try {
            redirectCache.replaceVersion(shortCode);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Coordination unconfirmed: operation=recover category=committed-cache-unconfirmed shortCode={}",
                    shortCode);
            throw exception;
        }
        LOGGER.info("Coordination recovered: operation=recover category=cache-confirmed shortCode={}", shortCode);
    }

    private void insertWithCollisionRetries(ShortLinkEntity entity) {
        for (int attempt = 1; attempt <= MAX_SHORT_CODE_INSERT_ATTEMPTS; attempt++) {
            long issuedId = shortCodeIdIssuer.issue();
            try {
                entity.setShortCode(shortCodeEncoder.encode(issuedId));
            } catch (IllegalArgumentException exception) {
                LOGGER.error("Short-code generation failed: category=encoding");
                throw new ShortCodeGenerationException();
            }

            try {
                mappingWriter.insertConfirmed(entity);
                return;
            } catch (ShortCodeCollisionException exception) {
                if (attempt == MAX_SHORT_CODE_INSERT_ATTEMPTS) {
                    LOGGER.error("Short-code generation failed: category=collision-exhausted");
                    throw new ShortCodeGenerationException();
                }
                com.example.shortlink.logging.SafeOperationalLog.sampled(LOGGER,
                        com.example.shortlink.logging.SafeOperationalLog.Category.GENERATION);
            }
        }
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
                throw new InvalidRequestException(
                        "originalUrl must be an absolute HTTP or HTTPS URI with a host.");
            }
        } catch (URISyntaxException exception) {
            throw new InvalidRequestException("originalUrl is not a valid URI.");
        }
    }
}
