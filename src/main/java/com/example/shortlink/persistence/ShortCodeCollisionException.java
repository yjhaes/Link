package com.example.shortlink.persistence;


/** The mapping's short-code primary key already exists; other failures keep their original type. */
public class ShortCodeCollisionException extends RuntimeException {
    public ShortCodeCollisionException(Throwable cause) {
        super("Short-code primary-key collision.", cause);
    }
}
