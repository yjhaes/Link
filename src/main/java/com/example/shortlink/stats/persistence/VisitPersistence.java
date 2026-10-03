package com.example.shortlink.stats.persistence;
import com.example.shortlink.stats.VisitEvent;

/** Synchronous persistence confirmation; a normal return is safe to acknowledge. */
public interface VisitPersistence {
    enum Outcome {
        SAVED,
        DUPLICATE
    }

    Outcome persist(VisitEvent event);
}
