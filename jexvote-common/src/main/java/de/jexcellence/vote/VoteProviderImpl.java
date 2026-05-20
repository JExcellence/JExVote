package de.jexcellence.vote;

import de.jexcellence.vote.api.VoteProvider;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.model.Vote;
import de.jexcellence.vote.service.VoteLeaderboardService;
import de.jexcellence.vote.service.VoteService;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class VoteProviderImpl implements VoteProvider {

    private final VoteService voteService;
    private final VoteLeaderboardService leaderboardService;

    public VoteProviderImpl(@NotNull VoteService voteService,
                            @NotNull VoteLeaderboardService leaderboardService) {
        this.voteService = voteService;
        this.leaderboardService = leaderboardService;
    }

    @Override
    public CompletableFuture<Boolean> submitVote(@NotNull String playerName, @NotNull String serviceName) {
        Vote vote = new Vote(playerName, serviceName, "api", Instant.now());
        return voteService.processVote(vote);
    }

    @Override
    public CompletableFuture<Integer> getTotalVotes(@NotNull UUID playerUuid) {
        return voteService.getPlayerStats(playerUuid).thenApply(VoteSnapshot::totalVotes);
    }

    @Override
    public CompletableFuture<Integer> getCurrentStreak(@NotNull UUID playerUuid) {
        return voteService.getPlayerStats(playerUuid).thenApply(VoteSnapshot::currentStreak);
    }

    @Override
    public CompletableFuture<Integer> getVotePoints(@NotNull UUID playerUuid) {
        return voteService.getPlayerStats(playerUuid).thenApply(VoteSnapshot::votePoints);
    }

    @Override
    public CompletableFuture<List<VoteSnapshot>> getTopVoters(int limit) {
        return leaderboardService.getAllTimeTop(limit);
    }

    @Override
    public CompletableFuture<List<VoteSnapshot>> getMonthlyTopVoters(int limit) {
        return leaderboardService.getMonthlyTop(limit);
    }
}
