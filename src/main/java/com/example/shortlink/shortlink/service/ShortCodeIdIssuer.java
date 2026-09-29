package com.example.shortlink.shortlink.service;

@FunctionalInterface
public interface ShortCodeIdIssuer {

    long issue();
}
