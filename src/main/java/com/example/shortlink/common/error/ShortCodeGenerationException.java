package com.example.shortlink.common.error;

public class ShortCodeGenerationException extends RuntimeException {

    public ShortCodeGenerationException() {
        super("A unique short code could not be generated.");
    }
}
