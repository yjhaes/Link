package com.example.shortlink.stats.collection;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class VisitIdentity {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final SecureRandom random = new SecureRandom();
    private final byte[] key;
    private final int version;

    public VisitIdentity(String key, int version) {
        this.key = Base64.getDecoder().decode(key);
        this.version = version;
    }

    public Identity identify(String code, List<String> values) throws GeneralSecurityException {
        byte[] bytes = values.size() == 1 ? decode(values.get(0)) : null;
        boolean fresh = bytes == null;
        if (fresh) {
            bytes = new byte[16];
            random.nextBytes(bytes);
        }
        byte[] purpose = "short-link-visitor".getBytes(StandardCharsets.US_ASCII);
        byte[] shortCode = code.getBytes(StandardCharsets.US_ASCII);
        byte[] input =
                ByteBuffer.allocate(purpose.length + 4 + 4 + shortCode.length + 16)
                        .put(purpose)
                        .putInt(version)
                        .putInt(shortCode.length)
                        .put(shortCode)
                        .put(bytes)
                        .array();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return new Identity(mac.doFinal(input), fresh ? ENCODER.encodeToString(bytes) : null);
    }

    private byte[] decode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{22}")) return null;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            return bytes.length == 16 && ENCODER.encodeToString(bytes).equals(value) ? bytes : null;
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    public record Identity(byte[] hash, String newCookie) {}
}
