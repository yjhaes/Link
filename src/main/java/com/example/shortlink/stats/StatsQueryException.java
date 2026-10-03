package com.example.shortlink.stats;


public class StatsQueryException extends RuntimeException {
    public enum Reason {
        BUSY,
        TIMEOUT,
        DATABASE
    }

    private final Reason reason;

    public StatsQueryException(Reason reason) {
        super("Statistics query failed.");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
