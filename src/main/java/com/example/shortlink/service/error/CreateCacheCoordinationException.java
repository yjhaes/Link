package com.example.shortlink.service.error;

public class CreateCacheCoordinationException extends RuntimeException {

    private final String shortCode;

    public CreateCacheCoordinationException(String shortCode, Throwable cause) {
        super("Database creation committed; cache coordination unconfirmed.", cause);
        this.shortCode = shortCode;
    }

    public String shortCode() {
        return shortCode;
    }
}
