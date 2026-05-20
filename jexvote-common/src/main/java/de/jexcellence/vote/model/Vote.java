package de.jexcellence.vote.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;

public record Vote(
        @NotNull String username,
        @NotNull String serviceName,
        @NotNull String address,
        @NotNull Instant timestamp
) {}
