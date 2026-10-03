package com.example.shortlink.stats;

/** Synchronous persistence confirmation; a normal return is safe to acknowledge. */
public interface VisitPersistence {
    enum Outcome {
        SAVED,
        DUPLICATE
    }

    Outcome persist(VisitEvent event);
}
