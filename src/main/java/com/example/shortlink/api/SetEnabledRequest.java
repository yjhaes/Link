package com.example.shortlink.api;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public record SetEnabledRequest(@NotNull @JsonDeserialize(using = EnabledDeserializer.class) Boolean enabled) {
}
