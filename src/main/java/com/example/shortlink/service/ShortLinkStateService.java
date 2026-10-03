package com.example.shortlink.service;

import com.example.shortlink.cache.RedirectCache;
import com.example.shortlink.persistence.ShortLinkEntity;
import com.example.shortlink.persistence.ShortLinkMapper;
import com.example.shortlink.service.error.LinkExpiredException;
import com.example.shortlink.service.error.LinkNotFoundException;
import com.example.shortlink.service.error.LinkStateConflictException;
import com.example.shortlink.service.error.StateCacheCoordinationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.ZoneOffset;

@Service
public class ShortLinkStateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShortLinkStateService.class);
    private static final int MAX_COORDINATION_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MILLIS = 50;
    private final ShortLinkMapper mapper;
    private final RedirectCache cache;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final RetryWait retryWait;

    @Autowired
    public ShortLinkStateService(
            ShortLinkMapper mapper,
            RedirectCache cache,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this(mapper, cache, transactionManager, clock, Thread::sleep);
    }

    ShortLinkStateService(
            ShortLinkMapper mapper,
            RedirectCache cache,
            PlatformTransactionManager transactionManager,
            Clock clock,
            RetryWait retryWait) {
        this.mapper = mapper;
        this.cache = cache;
        this.clock = clock;
        this.retryWait = retryWait;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void setEnabled(String shortCode, boolean enabled) {
        if (shortCode == null || !shortCode.matches("[A-Za-z0-9]{4,8}")) {
            throw new LinkNotFoundException();
        }
        transaction.executeWithoutResult(
                status -> {
                    ShortLinkEntity mapping = mapper.selectForUpdate(shortCode);
                    if (mapping == null) {
                        throw new LinkNotFoundException();
                    }
                    // Read the clock only after acquiring the row lock: waiting may cross expiry.
                    if (mapping.getExpiresAt() != null
                            && !clock.instant()
                                    .isBefore(mapping.getExpiresAt().toInstant(ZoneOffset.UTC))) {
                        throw new LinkExpiredException();
                    }
                    if (mapping.isEnabled() == enabled) {
                        throw new LinkStateConflictException(enabled);
                    }
                    if (mapper.updateEnabled(shortCode, enabled) != 1) {
                        throw new IllegalStateException(
                                "MySQL did not confirm updating the short-link state.");
                    }
                });
        coordinateCache(shortCode);
    }

    private void coordinateCache(String shortCode) {
        for (int attempt = 1; ; attempt++) {
            try {
                cache.replaceVersion(shortCode);
                return;
            } catch (RuntimeException failure) {
                if (attempt == MAX_COORDINATION_ATTEMPTS) {
                    throw unconfirmed(shortCode, failure);
                }
                LOGGER.warn(
                        "Cache coordination attempt {} failed for short code {}; retrying.",
                        attempt,
                        shortCode);
                try {
                    retryWait.pause(RETRY_DELAY_MILLIS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw unconfirmed(shortCode, interrupted);
                }
            }
        }
    }

    private StateCacheCoordinationException unconfirmed(String shortCode, Throwable cause) {
        LOGGER.error(
                "Database state update committed for short code {}; cache coordination unconfirmed."
                        + " Recover coordination using this short code.",
                shortCode,
                cause);
        return new StateCacheCoordinationException(shortCode, cause);
    }

    @FunctionalInterface
    interface RetryWait {
        void pause(long millis) throws InterruptedException;
    }
}
