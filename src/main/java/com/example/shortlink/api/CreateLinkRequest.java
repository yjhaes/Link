package com.example.shortlink.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;

public record CreateLinkRequest(
        @NotBlank String originalUrl,
        @JsonDeserialize(using = ValidMinutesDeserializer.class)
        Integer validMinutes) {
}
