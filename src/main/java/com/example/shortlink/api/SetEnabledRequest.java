package com.example.shortlink.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.NotNull;

@JsonDeserialize(using = SetEnabledRequestDeserializer.class)
public record SetEnabledRequest(@NotNull Boolean enabled) {}
