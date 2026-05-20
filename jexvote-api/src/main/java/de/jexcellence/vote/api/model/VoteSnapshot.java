package de.jexcellence.vote.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record VoteSnapshot(
        @NotNull UUID playerUuid,
        @Nullable String playerName,
        int totalVotes,
        int monthlyVotes,
        int currentStreak,
        int highestStreak,
        int votePoints,
        @Nullable Instant lastVoteAt
) {}
