package com.example.shortlink.service;

import com.example.shortlink.service.error.InvalidRequestException;
import com.example.shortlink.service.error.CreateCacheCoordinationException;
import com.example.shortlink.service.error.LinkDisabledException;
import com.example.shortlink.service.error.LinkExpiredException;
import com.example.shortlink.service.error.LinkNotFoundException;
import com.example.shortlink.service.error.ShortCodeGenerationException;
import com.example.shortlink.persistence.ShortLinkEntity;
import com.example.shortlink.persistence.ShortLinkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

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

    // MyBatis commits each non-transactional insert before returning. Suspend any caller transaction
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
        try {
            redirectCache.replaceVersion(entity.getShortCode());
        } catch (RuntimeException exception) {
            LOGGER.error("Database creation committed for short code {}; cache coordination unconfirmed. "
                    + "Recover coordination using this short code.", entity.getShortCode(), exception);
            throw new CreateCacheCoordinationException(entity.getShortCode(), exception);
        }

        return new CreatedShortLink(entity.getShortCode(), expiresAtInstant);
    }

    /** Internal maintenance entry point: call only after the mapping's database commit is confirmed. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recoverCacheCoordination(String shortCode) {
        if (shortCode == null || !shortCode.matches("[A-Za-z0-9]{4,8}")
                || shortLinkMapper.selectById(shortCode) == null) {
            throw new LinkNotFoundException();
        }
        try {
            redirectCache.replaceVersion(shortCode);
        } catch (RuntimeException exception) {
            LOGGER.error("Cache coordination recovery unconfirmed for short code {}; retry using the same code.",
                    shortCode, exception);
            throw exception;
        }
        LOGGER.info("Cache coordination recovery confirmed for short code {}.", shortCode);
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
                if (shortLinkMapper.insert(entity) != 1) {
                    throw new IllegalStateException("MySQL did not confirm inserting the short-link mapping.");
                }
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

        RedirectCacheRead cachedRedirect = null;
        try {
            cachedRedirect = redirectCache.find(code);
        } catch (RuntimeException exception) {
            LOGGER.warn("Redirect cache lookup failed for short code {}; falling back to MySQL.", code, exception);
        }
        if (cachedRedirect != null && cachedRedirect.status() == RedirectCacheRead.Status.NOT_FOUND) {
            throw new LinkNotFoundException();
        }
        if (cachedRedirect != null && cachedRedirect.status() == RedirectCacheRead.Status.EXPIRED) {
            throw new LinkExpiredException();
        }
        if (cachedRedirect != null && (cachedRedirect.status() == RedirectCacheRead.Status.REDIRECT
                || cachedRedirect.status() == RedirectCacheRead.Status.DISABLED)) {
            RedirectCacheEntry cacheEntry = cachedRedirect.entry();
            if (isExpired(cacheEntry == null ? null : cacheEntry.expiresAt())) {
                try {
                    redirectCache.storeIfVersion(code, cachedRedirect.generation(), RedirectCacheRead.Status.EXPIRED, null);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Could not cache expired redirect result for short code {}.", code, exception);
                }
                throw new LinkExpiredException();
            }
            if (cachedRedirect.status() == RedirectCacheRead.Status.DISABLED) {
                throw new LinkDisabledException();
            }
            return cacheEntry.originalUrl();
        }

        ShortLinkEntity entity = shortLinkMapper.selectById(code);
        if (entity == null) {
            cacheResultIfVersion(code, cachedRedirect, RedirectCacheRead.Status.NOT_FOUND, null);
            throw new LinkNotFoundException();
        }
        Instant expiresAt = entity.getExpiresAt() == null
                ? null
                : entity.getExpiresAt().toInstant(ZoneOffset.UTC);
        if (isExpired(expiresAt)) {
            cacheResultIfVersion(code, cachedRedirect, RedirectCacheRead.Status.EXPIRED, null);
            throw new LinkExpiredException();
        }
        if (!entity.isEnabled()) {
            cacheResultIfVersion(code, cachedRedirect, RedirectCacheRead.Status.DISABLED,
                    new RedirectCacheEntry(null, expiresAt));
            throw new LinkDisabledException();
        }

        cacheResultIfVersion(code, cachedRedirect, RedirectCacheRead.Status.REDIRECT,
                new RedirectCacheEntry(entity.getOriginalUrl(), expiresAt));
        return entity.getOriginalUrl();
    }

    private void cacheResultIfVersion(
            String code, RedirectCacheRead read, RedirectCacheRead.Status status, RedirectCacheEntry entry) {
        if (read != null
                && (read.status() == RedirectCacheRead.Status.MISS
                || read.status() == RedirectCacheRead.Status.PLACEHOLDER)) {
            try {
                redirectCache.storeIfVersion(code, read.generation(), status, entry);
            } catch (RuntimeException exception) {
                LOGGER.warn("Redirect cache write failed for short code {} and result {}; returning the MySQL result.",
                        code, status, exception);
            }
        }
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
