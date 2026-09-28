package com.example.shortlink.shortlink.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class ShortCodeGenerator {

    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        StringBuilder code = new StringBuilder(8);
        for (int index = 0; index < 8; index++) {
            code.append(ALPHABET[secureRandom.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }
}
