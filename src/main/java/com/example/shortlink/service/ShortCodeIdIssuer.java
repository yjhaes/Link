package com.example.shortlink.service;

@FunctionalInterface
public interface ShortCodeIdIssuer {

    long issue();
}
