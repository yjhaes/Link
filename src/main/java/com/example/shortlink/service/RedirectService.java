package com.example.shortlink.service;


import com.example.shortlink.cache.RedirectCache;
import com.example.shortlink.cache.RedirectCacheEntry;
import com.example.shortlink.cache.RedirectCacheProperties;
import com.example.shortlink.cache.RedirectCacheRead;
import com.example.shortlink.persistence.ShortLinkEntity;
import com.example.shortlink.persistence.ShortLinkMapper;
import com.example.shortlink.service.error.LinkDisabledException;
import com.example.shortlink.service.error.LinkExpiredException;
import com.example.shortlink.service.error.LinkNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class RedirectService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedirectService.class);
    private final ShortLinkMapper shortLinkMapper;
    private final Clock clock;
    private final RedirectCache redirectCache;
    private final ConcurrentHashMap<LoadKey, CompletableFuture<LoadedRedirect>> redirectLoads =
            new ConcurrentHashMap<>();
    private final long loadWaitNanos;

    public RedirectService(
            ShortLinkMapper shortLinkMapper,
            Clock clock,
            RedirectCache redirectCache,
            RedirectCacheProperties properties) {
        this.shortLinkMapper = shortLinkMapper;
        this.clock = clock;
        this.redirectCache = redirectCache;
        this.loadWaitNanos = properties.getLoadWait().toNanos();
    }

    public RedirectDecision decide(String code) {
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
                // Check again after winning ownership, so that completed round does not cause
                // another SQL.
                RedirectCacheRead latest = readCache(code);
                LoadedRedirect result =
                        hasResult(latest)
                                ? new LoadedRedirect(
                                        latest.status(), latest.entry(), latest.generation())
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
            return hasResult(retry)
                    ? useResult(code, retry)
                    : useResult(code, loadRedirect(code, retry));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for a redirect load.", exception);
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
            LOGGER.warn(
                    "Redirect cache lookup failed for short code {}; falling back to MySQL.",
                    code,
                    exception);
            return null;
        }
    }

    private boolean hasResult(RedirectCacheRead read) {
        return read != null
                && read.status() != RedirectCacheRead.Status.MISS
                && read.status() != RedirectCacheRead.Status.PLACEHOLDER;
    }

    private LoadedRedirect loadRedirect(String code, RedirectCacheRead read) {
        ShortLinkEntity entity = shortLinkMapper.selectById(code);
        RedirectCacheRead.Status status;
        RedirectCacheEntry entry = null;
        if (entity == null) {
            status = RedirectCacheRead.Status.NOT_FOUND;
        } else {
            Instant expiresAt =
                    entity.getExpiresAt() == null
                            ? null
                            : entity.getExpiresAt().toInstant(ZoneOffset.UTC);
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

    private RedirectDecision useResult(String code, RedirectCacheRead result) {
        return useResult(
                code, new LoadedRedirect(result.status(), result.entry(), result.generation()));
    }

    private RedirectDecision useResult(String code, LoadedRedirect result) {
        if (result.status() == RedirectCacheRead.Status.NOT_FOUND) {
            throw new LinkNotFoundException();
        }
        if (result.status() == RedirectCacheRead.Status.EXPIRED) {
            throw new LinkExpiredException();
        }
        RedirectCacheEntry entry = result.entry();
        Instant decidedAt = clock.instant();
        if (isExpired(entry == null ? null : entry.expiresAt(), decidedAt)) {
            if (result.generation() != null) {
                try {
                    redirectCache.storeIfVersion(
                            code, result.generation(), RedirectCacheRead.Status.EXPIRED, null);
                } catch (RuntimeException exception) {
                    LOGGER.warn(
                            "Could not cache expired redirect result for short code {}.",
                            code,
                            exception);
                }
            }
            throw new LinkExpiredException();
        }
        if (result.status() == RedirectCacheRead.Status.DISABLED) {
            throw new LinkDisabledException();
        }
        return new RedirectDecision(entry.originalUrl(), decidedAt);
    }

    private record LoadKey(String code, String generation) {}

    private record LoadedRedirect(
            RedirectCacheRead.Status status, RedirectCacheEntry entry, String generation) {}

    private void cacheResultIfVersion(
            String code,
            RedirectCacheRead read,
            RedirectCacheRead.Status status,
            RedirectCacheEntry entry) {
        if (read != null
                && (read.status() == RedirectCacheRead.Status.MISS
                        || read.status() == RedirectCacheRead.Status.PLACEHOLDER)) {
            try {
                redirectCache.storeIfVersion(code, read.generation(), status, entry);
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "Redirect cache write failed for short code {} and result {}; returning the"
                                + " MySQL result.",
                        code,
                        status,
                        exception);
            }
        }
    }

    private boolean isExpired(Instant expiresAt) {
        return expiresAt != null && isExpired(expiresAt, clock.instant());
    }

    private boolean isExpired(Instant expiresAt, Instant checkedAt) {
        return expiresAt != null && !checkedAt.isBefore(expiresAt);
    }
}
