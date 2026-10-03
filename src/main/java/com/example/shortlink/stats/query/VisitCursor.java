package com.example.shortlink.stats.query;

import java.time.LocalDateTime;

/** Stable descending database position, independent of API cursor representation. */
public record VisitCursor(LocalDateTime occurredAt, long id) {}
