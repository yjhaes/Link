package com.example.shortlink.stats;

/** Controlled failure without driver text, request data or secrets. */
public final class VisitPersistenceException extends RuntimeException {
    public enum Failure { BUSY, TRANSIENT, PERMANENT, UNCERTAIN }
    private final Failure failure;
    public VisitPersistenceException(Failure failure) {
        super("Visit persistence " + failure);
        this.failure = failure;
    }
    public Failure failure() { return failure; }
}
