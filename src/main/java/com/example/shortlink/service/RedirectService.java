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
@org.springframework.boot.context.properties.EnableConfigurationProperties(RedirectLoadProperties.class)
public class RedirectService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedirectService.class);
    private final ShortLinkMapper shortLinkMapper;
    private final Clock clock;
    private final RedirectCache redirectCache;
    private final ConcurrentHashMap<LoadKey, CompletableFuture<LoadedRedirect>> redirectLoads =
            new ConcurrentHashMap<>();
    private final long loadWaitNanos;
    private final java.util.concurrent.Semaphore queryPermits;
    private final java.util.concurrent.atomic.AtomicInteger queries = new java.util.concurrent.atomic.AtomicInteger();
    private io.micrometer.core.instrument.MeterRegistry meters;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void observe(io.micrometer.core.instrument.MeterRegistry registry) {
        meters = registry;
        registry.gauge("shortlink.redirect.load.inflight", queries);
    }
    private void count(String name, String result) {
        if (meters != null) meters.counter(name, "result", result).increment();
    }

    public RedirectService(
            ShortLinkMapper shortLinkMapper,
            Clock clock,
            RedirectCache redirectCache,
            RedirectCacheProperties properties) {
        this(shortLinkMapper, clock, redirectCache, properties, new RedirectLoadProperties(4));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RedirectService(ShortLinkMapper shortLinkMapper, Clock clock, RedirectCache redirectCache,
            RedirectCacheProperties properties, RedirectLoadProperties loadProperties) {
        this.queryPermits = new java.util.concurrent.Semaphore(loadProperties.maxConcurrent());
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
            RedirectCacheRead value = redirectCache.find(code);
            if (value != null) com.example.shortlink.logging.SafeOperationalLog.recovered(LOGGER,
                    com.example.shortlink.logging.SafeOperationalLog.Category.CACHE_READ);
            count("shortlink.cache.read", value == null ? "unavailable" : value.status().name().toLowerCase(java.util.Locale.ROOT));
            return value;
        } catch (RuntimeException exception) {
            count("shortlink.cache.read", "failed");
            com.example.shortlink.logging.SafeOperationalLog.sampled(LOGGER,
                    com.example.shortlink.logging.SafeOperationalLog.Category.CACHE_READ);
            return null;
        }
    }

    private boolean hasResult(RedirectCacheRead read) {
        return read != null
                && read.status() != RedirectCacheRead.Status.MISS
                && read.status() != RedirectCacheRead.Status.PLACEHOLDER;
    }

    private LoadedRedirect loadRedirect(String code, RedirectCacheRead read) {
        if (!queryPermits.tryAcquire()) {
            if (meters != null) meters.counter("shortlink.redirect.load.rejected").increment();
            throw new com.example.shortlink.service.error.RedirectLoadBusyException();
        }
        queries.incrementAndGet();
        ShortLinkEntity entity;
        try {
            entity = shortLinkMapper.selectById(code);
        } finally {
            // Release on the actual query's completion, before cache coordination or shared-result use.
            queries.decrementAndGet();
            queryPermits.release();
        }
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
                    boolean stored = redirectCache.storeIfVersion(
                            code, result.generation(), RedirectCacheRead.Status.EXPIRED, null);
                    count("shortlink.cache.write", stored ? "stored" : "version_changed");
                    com.example.shortlink.logging.SafeOperationalLog.recovered(LOGGER,
                            com.example.shortlink.logging.SafeOperationalLog.Category.CACHE_WRITE);
                } catch (RuntimeException exception) {
                    count("shortlink.cache.write", "failed");
                    com.example.shortlink.logging.SafeOperationalLog.sampled(LOGGER,
                            com.example.shortlink.logging.SafeOperationalLog.Category.CACHE_WRITE);
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
                boolean stored = redirectCache.storeIfVersion(code, read.generation(), status, entry);
                count("shortlink.cache.write", stored ? "stored" : "version_changed");
                com.example.shortlink.logging.SafeOperationalLog.recovered(LOGGER,
                        com.example.shortlink.logging.SafeOperationalLog.Category.CACHE_WRITE);
            } catch (RuntimeException exception) {
                count("shortlink.cache.write", "failed");
                com.example.shortlink.logging.SafeOperationalLog.sampled(LOGGER,
                        com.example.shortlink.logging.SafeOperationalLog.Category.CACHE_WRITE);
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
