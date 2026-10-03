package com.example.shortlink.stats;


import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Contains only frozen, minimized data; never retains browser identifiers or requests. */
public record VisitEvent(
        UUID eventId,
        String shortCode,
        Instant occurredAt,
        LocalDate statDate,
        byte[] visitorHash,
        int visitorKeyVersion,
        String peerIpNetwork,
        String userAgent,
        String refererHost) {
    public VisitEvent {
        visitorHash = visitorHash.clone();
    }

    @Override
    public byte[] visitorHash() {
        return visitorHash.clone();
    }
}
