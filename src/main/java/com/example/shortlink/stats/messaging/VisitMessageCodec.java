package com.example.shortlink.stats.messaging;


import com.example.shortlink.stats.VisitEvent;
import com.example.shortlink.stats.VisitMetadata;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Strict, versioned JSON boundary; errors intentionally never retain the payload. */
public final class VisitMessageCodec {
    public static final int MAX_BYTES = 16 * 1024;
    private static final Set<String> FIELDS =
            Set.of(
                    "schemaVersion",
                    "eventId",
                    "shortCode",
                    "occurredAt",
                    "statDate",
                    "visitorHash",
                    "visitorKeyVersion",
                    "peerIpNetwork",
                    "userAgent",
                    "refererHost");
    private static final DateTimeFormatter UTC_MILLIS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private final ObjectMapper json =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public byte[] encode(VisitEvent event) {
        var n = json.createObjectNode();
        n.put("schemaVersion", 1);
        n.put("eventId", event.eventId().toString());
        n.put("shortCode", event.shortCode());
        n.put("occurredAt", UTC_MILLIS.format(event.occurredAt()));
        n.put("statDate", event.statDate().toString());
        n.put("visitorHash", Base64.getEncoder().encodeToString(event.visitorHash()));
        n.put("visitorKeyVersion", event.visitorKeyVersion());
        n.put("peerIpNetwork", event.peerIpNetwork());
        n.put("userAgent", event.userAgent());
        n.put("refererHost", event.refererHost());
        try {
            byte[] bytes = json.writeValueAsBytes(n);
            decode(bytes);
            return bytes;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    public VisitEvent decode(byte[] bytes) {
        try {
            if (bytes == null || bytes.length > MAX_BYTES) throw invalid();
            String utf8 =
                    java.nio.charset.StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes))
                            .toString();
            JsonNode n = json.readTree(utf8);
            if (n == null || !n.isObject()) throw invalid();
            var names = new HashSet<String>();
            n.fieldNames().forEachRemaining(names::add);
            if (!FIELDS.containsAll(names) || integer(n, "schemaVersion") != 1) throw invalid();
            String id = required(n, "eventId");
            UUID uuid = UUID.fromString(id);
            if (!uuid.toString().equals(id)) throw invalid();
            String code = required(n, "shortCode");
            if (!code.matches("[A-Za-z0-9]{4,8}")) throw invalid();
            String timestamp = required(n, "occurredAt");
            if (!timestamp.matches(
                    "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z"))
                throw invalid();
            Instant at = Instant.parse(timestamp);
            if (!UTC_MILLIS.format(at).equals(timestamp)) throw invalid();
            LocalDate date = LocalDate.parse(required(n, "statDate"));
            if (!date.toString().equals(required(n, "statDate"))
                    || !at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().equals(date))
                throw invalid();
            String hashText = required(n, "visitorHash");
            byte[] hash = Base64.getDecoder().decode(hashText);
            if (hash.length != 32 || !Base64.getEncoder().encodeToString(hash).equals(hashText))
                throw invalid();
            int version = integer(n, "visitorKeyVersion");
            if (version < 1 || version > 65535) throw invalid();
            String network = optional(n, "peerIpNetwork", 49),
                    agent = optional(n, "userAgent", 512),
                    host = optional(n, "refererHost", 253);
            if (network != null) {
                String suffix = network.contains(":") ? "/48" : "/24";
                if (!network.endsWith(suffix)
                        || !network.equals(
                                VisitMetadata.peerNetwork(
                                        network.substring(0, network.length() - suffix.length()))))
                    throw invalid();
            }
            if (agent != null && !agent.equals(VisitMetadata.userAgent(agent))) throw invalid();
            if (host != null
                    && !host.equals(
                            VisitMetadata.refererHost(
                                    "http://" + (host.contains(":") ? "[" + host + "]" : host))))
                throw invalid();
            return new VisitEvent(uuid, code, at, date, hash, version, network, agent, host);
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private int integer(JsonNode n, String key) {
        JsonNode value = n.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        return value.intValue();
    }

    private String required(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || !v.isTextual()) throw invalid();
        return v.textValue();
    }

    private String optional(JsonNode n, String key, int max) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return null;
        String s = required(n, key);
        if (s.codePointCount(0, s.length()) > max) throw invalid();
        return s;
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid visit message contract.");
    }
}
