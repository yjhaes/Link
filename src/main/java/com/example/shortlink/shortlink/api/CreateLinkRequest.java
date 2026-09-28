package com.example.shortlink.shortlink.api;

import jakarta.validation.constraints.NotBlank;

public record CreateLinkRequest(
        @NotBlank String originalUrl,
        Long validMinutes) {
}
