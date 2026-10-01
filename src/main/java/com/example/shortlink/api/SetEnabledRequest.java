package com.example.shortlink.api;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = SetEnabledRequestDeserializer.class)
public record SetEnabledRequest(@NotNull Boolean enabled) {
}
