package com.example.shortlink.service.error;


public class LinkStateConflictException extends RuntimeException {
    private final boolean enabled;

    public LinkStateConflictException(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }
}
