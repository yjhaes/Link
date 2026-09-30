package com.example.shortlink.service;

import java.util.Objects;

public record RedirectCacheRead(Status status, String generation, RedirectCacheEntry entry) {

    public enum Status {
        MISS,
        PLACEHOLDER,
        REDIRECT,
        NOT_FOUND,
        EXPIRED,
        DISABLED
    }

    public RedirectCacheRead {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(generation, "generation");
        if (generation.isBlank()) {
            throw new IllegalArgumentException("Redirect cache generation must not be blank.");
        }
        if (status == Status.REDIRECT
                && (entry == null || entry.originalUrl() == null || entry.originalUrl().isBlank())) {
            throw new IllegalArgumentException("Redirect cache reads require an original URL.");
        }
        if (status == Status.DISABLED && entry != null && entry.originalUrl() != null) {
            throw new IllegalArgumentException("Disabled cache reads cannot contain an original URL.");
        }
        if (status != Status.REDIRECT && status != Status.DISABLED && entry != null) {
            throw new IllegalArgumentException("This redirect cache status cannot contain a snapshot.");
        }
    }

    public static RedirectCacheRead miss(String generation) {
        return new RedirectCacheRead(Status.MISS, generation, null);
    }

    public static RedirectCacheRead placeholder(String generation) {
        return new RedirectCacheRead(Status.PLACEHOLDER, generation, null);
    }

    public static RedirectCacheRead redirect(String generation, RedirectCacheEntry entry) {
        return new RedirectCacheRead(Status.REDIRECT, generation, entry);
    }

    public static RedirectCacheRead result(Status status, String generation) {
        return result(status, generation, null);
    }

    public static RedirectCacheRead result(Status status, String generation, RedirectCacheEntry entry) {
        if (status == Status.MISS || status == Status.PLACEHOLDER || status == Status.REDIRECT) {
            throw new IllegalArgumentException("Use the matching redirect cache read factory.");
        }
        return new RedirectCacheRead(status, generation, entry);
    }
}
