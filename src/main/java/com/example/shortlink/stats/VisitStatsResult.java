package com.example.shortlink.stats;

import java.time.LocalDate;
import java.util.List;

/** Counts of recorded events and observed identities from one database snapshot. */
public record VisitStatsResult(long pv, long uv, List<Integer> identityVersions, List<Day> daily) {
    public record Day(LocalDate date, long pv, long uv, boolean isOngoing) { }
}
