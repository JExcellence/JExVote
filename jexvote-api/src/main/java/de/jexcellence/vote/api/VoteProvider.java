package de.jexcellence.vote.api;

import de.jexcellence.vote.api.model.VoteSnapshot;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface VoteProvider {

    CompletableFuture<Boolean> submitVote(@NotNull String playerName, @NotNull String serviceName);

    CompletableFuture<Integer> getTotalVotes(@NotNull UUID playerUuid);

    CompletableFuture<Integer> getCurrentStreak(@NotNull UUID playerUuid);

    CompletableFuture<Integer> getVotePoints(@NotNull UUID playerUuid);

    CompletableFuture<List<VoteSnapshot>> getTopVoters(int limit);

    CompletableFuture<List<VoteSnapshot>> getMonthlyTopVoters(int limit);

    /**
     * Live vote-party progress as {@code {current, target}}. Returns {@code {0, 0}}
     * when no party is active or the feature is disabled. Synchronous — reads an
     * in-memory counter, so it's safe to call from a render/tick.
     */
    default int[] getVotePartyProgress() {
        return new int[]{0, 0};
    }
}
