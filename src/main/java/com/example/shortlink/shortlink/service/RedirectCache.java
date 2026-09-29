package com.example.shortlink.shortlink.service;

import java.util.Optional;

public interface RedirectCache {

    Optional<String> findPermanent(String shortCode);

    void storePermanent(String shortCode, String originalUrl);
}
