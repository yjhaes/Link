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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
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

    private final ConcurrentHashMap<LoadKey, CompletableFuture<LoadedRedirect>> redirectLoads = new ConcurrentHashMap<>();
    private final long loadWaitNanos;

    public ShortLinkService(
            ShortLinkMapper shortLinkMapper,
            ShortCodeIdIssuer shortCodeIdIssuer,
            PermutedShortCodeEncoder shortCodeEncoder,
            Clock clock,
            RedirectCache redirectCache) {
        this(shortLinkMapper, shortCodeIdIssuer, shortCodeEncoder, clock, redirectCache, Duration.ofMillis(200));
    }

    @Autowired
    public ShortLinkService(
            ShortLinkMapper shortLinkMapper,
            ShortCodeIdIssuer shortCodeIdIssuer,
            PermutedShortCodeEncoder shortCodeEncoder,
            Clock clock,
            RedirectCache redirectCache,
            @Value("${short-link.redirect-cache.load-wait:200ms}") Duration loadWait) {
        if (loadWait == null || loadWait.isNegative() || loadWait.isZero()) {
            throw new IllegalArgumentException("Redirect load wait must be positive and finite.");
        }
        this.loadWaitNanos = loadWait.toNanos();
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

        RedirectCacheRead read = readCache(code);
        if (hasResult(read)) {
            return useResult(code, read);
        }
        if (read == null) {
            return useResult(code, loadRedirect(code, null));
        }

        LoadKey key = new LoadKey(code, read.generation());
        CompletableFuture<LoadedRedirect> task = new CompletableFuture<>();
        CompletableFuture<LoadedRedirect> existing = redirectLoads.putIfAbsent(key, task);
        if (existing == null) {
            try {
                // A request can be descheduled after its miss until a previous load has finished.
                // Check again after winning ownership, so that completed round does not cause another SQL.
                RedirectCacheRead latest = readCache(code);
                LoadedRedirect result = hasResult(latest)
                        ? new LoadedRedirect(latest.status(), latest.entry(), latest.generation())
                        : loadRedirect(code, latest);
                task.complete(result);
                return useResult(code, result);
            } catch (RuntimeException | Error failure) {
                task.completeExceptionally(failure);
                throw failure;
            } finally {
                redirectLoads.remove(key, task);
            }
        }
        try {
            return useResult(code, existing.get(loadWaitNanos, TimeUnit.NANOSECONDS));
        } catch (TimeoutException exception) {
            RedirectCacheRead retry = readCache(code);
            return hasResult(retry) ? useResult(code, retry) : useResult(code, loadRedirect(code, retry));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a redirect load.", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            if (exception.getCause() instanceof Error failure) {
                throw failure;
            }
            throw new IllegalStateException("Redirect load failed.", exception.getCause());
        }
    }

    private RedirectCacheRead readCache(String code) {
        try {
            return redirectCache.find(code);
        } catch (RuntimeException exception) {
            LOGGER.warn("Redirect cache lookup failed for short code {}; falling back to MySQL.", code, exception);
            return null;
        }
    }

    private boolean hasResult(RedirectCacheRead read) {
        return read != null && read.status() != RedirectCacheRead.Status.MISS
                && read.status() != RedirectCacheRead.Status.PLACEHOLDER;
    }

    private LoadedRedirect loadRedirect(String code, RedirectCacheRead read) {
        ShortLinkEntity entity = shortLinkMapper.selectById(code);
        RedirectCacheRead.Status status;
        RedirectCacheEntry entry = null;
        if (entity == null) {
            status = RedirectCacheRead.Status.NOT_FOUND;
        } else {
            Instant expiresAt = entity.getExpiresAt() == null
                    ? null : entity.getExpiresAt().toInstant(ZoneOffset.UTC);
            if (isExpired(expiresAt)) {
                status = RedirectCacheRead.Status.EXPIRED;
            } else if (!entity.isEnabled()) {
                status = RedirectCacheRead.Status.DISABLED;
                entry = new RedirectCacheEntry(null, expiresAt);
            } else {
                status = RedirectCacheRead.Status.REDIRECT;
                entry = new RedirectCacheEntry(entity.getOriginalUrl(), expiresAt);
            }
        }
        cacheResultIfVersion(code, read, status, entry);
        return new LoadedRedirect(status, entry, read == null ? null : read.generation());
    }

    private String useResult(String code, RedirectCacheRead result) {
        return useResult(code, new LoadedRedirect(result.status(), result.entry(), result.generation()));
    }

    private String useResult(String code, LoadedRedirect result) {
        if (result.status() == RedirectCacheRead.Status.NOT_FOUND) {
            throw new LinkNotFoundException();
        }
        if (result.status() == RedirectCacheRead.Status.EXPIRED) {
            throw new LinkExpiredException();
        }
        RedirectCacheEntry entry = result.entry();
        if (isExpired(entry == null ? null : entry.expiresAt())) {
            if (result.generation() != null) {
                try {
                    redirectCache.storeIfVersion(code, result.generation(), RedirectCacheRead.Status.EXPIRED, null);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Could not cache expired redirect result for short code {}.", code, exception);
                }
            }
            throw new LinkExpiredException();
        }
        if (result.status() == RedirectCacheRead.Status.DISABLED) {
            throw new LinkDisabledException();
        }
        return entry.originalUrl();
    }

    private record LoadKey(String code, String generation) { }

    private record LoadedRedirect(RedirectCacheRead.Status status, RedirectCacheEntry entry, String generation) { }
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
