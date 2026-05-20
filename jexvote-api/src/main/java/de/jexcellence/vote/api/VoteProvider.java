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
}
