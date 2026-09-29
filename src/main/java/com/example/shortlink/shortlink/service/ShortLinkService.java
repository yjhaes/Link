package com.example.shortlink.shortlink.service;

import com.example.shortlink.common.error.InvalidRequestException;
import com.example.shortlink.common.error.LinkDisabledException;
import com.example.shortlink.common.error.LinkExpiredException;
import com.example.shortlink.common.error.LinkNotFoundException;
import com.example.shortlink.common.error.ShortCodeGenerationException;
import com.example.shortlink.shortlink.persistence.ShortLinkEntity;
import com.example.shortlink.shortlink.persistence.ShortLinkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;

@Service
public class ShortLinkService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShortLinkService.class);
    private static final int MAX_VALID_MINUTES = 5_256_000;
    private static final int MAX_SHORT_CODE_INSERT_ATTEMPTS = 2;

    private final ShortLinkMapper shortLinkMapper;
    private final ShortCodeIdIssuer shortCodeIdIssuer;
    private final PermutedShortCodeEncoder shortCodeEncoder;
    private final Clock clock;
    private final RedirectCache redirectCache;

    public ShortLinkService(
            ShortLinkMapper shortLinkMapper,
            ShortCodeIdIssuer shortCodeIdIssuer,
            PermutedShortCodeEncoder shortCodeEncoder,
            Clock clock,
            RedirectCache redirectCache) {
        this.shortLinkMapper = shortLinkMapper;
        this.shortCodeIdIssuer = shortCodeIdIssuer;
        this.shortCodeEncoder = shortCodeEncoder;
        this.clock = clock;
        this.redirectCache = redirectCache;
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
        entity.setOriginalUrl(originalUrl);
        entity.setCreatedAt(createdAt);
        entity.setExpiresAt(expiresAtInstant == null
                ? null
                : LocalDateTime.ofInstant(expiresAtInstant, ZoneOffset.UTC));
        entity.setEnabled(true);
        insertWithCollisionRetries(entity);

        return new CreatedShortLink(entity.getShortCode(), expiresAtInstant);
    }

    private void insertWithCollisionRetries(ShortLinkEntity entity) {
        for (int attempt = 1; attempt <= MAX_SHORT_CODE_INSERT_ATTEMPTS; attempt++) {
            long issuedId = shortCodeIdIssuer.issue();
            try {
                entity.setShortCode(shortCodeEncoder.encode(issuedId));
            } catch (IllegalArgumentException exception) {
                LOGGER.error("Could not encode issued short-code ID {}.", issuedId, exception);
                throw new ShortCodeGenerationException();
            }

            try {
                shortLinkMapper.insert(entity);
                return;
            } catch (DuplicateKeyException exception) {
                if (!isShortCodePrimaryKeyCollision(exception)) {
                    throw exception;
                }
                if (attempt == MAX_SHORT_CODE_INSERT_ATTEMPTS) {
                    LOGGER.error("Short-code primary-key collision persisted after issuing a replacement ID.",
                            exception);
                    throw new ShortCodeGenerationException();
                }
                LOGGER.warn("Short-code primary-key collision for issued ID {}; retrying once.", issuedId, exception);
            }
        }
    }

    private boolean isShortCodePrimaryKeyCollision(DuplicateKeyException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && isPrimaryKeyDuplicate(sqlException)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPrimaryKeyDuplicate(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            if (current.getErrorCode() == 1062 && namesPrimaryKey(current.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private boolean namesPrimaryKey(String message) {
        if (message == null) {
            return false;
        }

        String lowerCaseMessage = message.toLowerCase(Locale.ROOT);
        int keyNameStart = lowerCaseMessage.lastIndexOf("for key ");
        if (keyNameStart < 0) {
            return false;
        }

        String keyName = lowerCaseMessage.substring(keyNameStart + "for key ".length())
                .replace("'", "")
                .replace("`", "")
                .replace("\"", "")
                .trim();
        return keyName.equals("primary") || keyName.endsWith(".primary");
    }

    public String findOriginalUrl(String code) {
        if (code == null || !code.matches("[A-Za-z0-9]{4,8}")) {
            throw new LinkNotFoundException();
        }

        Optional<RedirectCacheEntry> cachedRedirect;
        try {
            cachedRedirect = redirectCache.find(code);
        } catch (RuntimeException exception) {
            LOGGER.warn("Redirect cache lookup failed for short code {}; falling back to MySQL.", code, exception);
            cachedRedirect = Optional.empty();
        }
        if (cachedRedirect.isPresent()) {
            RedirectCacheEntry cacheEntry = cachedRedirect.get();
            if (isExpired(cacheEntry.expiresAt())) {
                try {
                    redirectCache.delete(code);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Could not delete expired redirect cache entry for short code {}.", code, exception);
                }
                throw new LinkExpiredException();
            }
            return cacheEntry.originalUrl();
        }

        ShortLinkEntity entity = shortLinkMapper.selectById(code);
        if (entity == null) {
            throw new LinkNotFoundException();
        }
        Instant expiresAt = entity.getExpiresAt() == null
                ? null
                : entity.getExpiresAt().toInstant(ZoneOffset.UTC);
        if (isExpired(expiresAt)) {
            throw new LinkExpiredException();
        }
        if (!entity.isEnabled()) {
            throw new LinkDisabledException();
        }

        try {
            redirectCache.store(code, new RedirectCacheEntry(entity.getOriginalUrl(), expiresAt));
        } catch (RuntimeException exception) {
            LOGGER.warn("Redirect cache write failed for short code {}; returning the MySQL result.", code, exception);
        }
        return entity.getOriginalUrl();
    }

    private boolean isExpired(Instant expiresAt) {
        return expiresAt != null && !clock.instant().isBefore(expiresAt);
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
