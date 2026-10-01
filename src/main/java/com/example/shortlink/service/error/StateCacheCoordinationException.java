package com.example.shortlink.service.error;

public class StateCacheCoordinationException extends RuntimeException {
    private final String shortCode;

    public StateCacheCoordinationException(String shortCode, Throwable cause) {
        super("Database state update committed; cache coordination unconfirmed.", cause);
        this.shortCode = shortCode;
    }

    public String shortCode() {
        return shortCode;
    }
}
