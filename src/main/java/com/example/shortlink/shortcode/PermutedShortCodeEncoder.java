package com.example.shortlink.shortcode;


import org.springframework.stereotype.Component;

import java.math.BigInteger;

@Component
public class PermutedShortCodeEncoder {

    public static final long MAX_SUPPORTED_ID = 218_340_105_584_896L;

    private static final char[] BASE62_ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final BigInteger RADIX = BigInteger.valueOf(BASE62_ALPHABET.length);
    private static final BigInteger MODULUS = BigInteger.valueOf(MAX_SUPPORTED_ID);
    private static final BigInteger MULTIPLIER = new BigInteger("134941606358707");
    private static final BigInteger OFFSET = new BigInteger("90439432943237");

    public String encode(long id) {
        if (id < 1 || id > MAX_SUPPORTED_ID) {
            throw new IllegalArgumentException(
                    "ID must be between 1 and " + MAX_SUPPORTED_ID + ".");
        }

        BigInteger permutedValue =
                MULTIPLIER.multiply(BigInteger.valueOf(id - 1)).add(OFFSET).mod(MODULUS);
        return toShortCode(permutedValue);
    }

    private String toShortCode(BigInteger value) {
        StringBuilder reversed = new StringBuilder(8);
        BigInteger remaining = value;

        do {
            BigInteger[] division = remaining.divideAndRemainder(RADIX);
            reversed.append(BASE62_ALPHABET[division[1].intValue()]);
            remaining = division[0];
        } while (remaining.signum() > 0);

        String shortCode = reversed.reverse().toString();
        return shortCode.length() >= 4 ? shortCode : "0".repeat(4 - shortCode.length()) + shortCode;
    }
}
