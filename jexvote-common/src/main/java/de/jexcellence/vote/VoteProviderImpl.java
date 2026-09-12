package de.jexcellence.vote;

import de.jexcellence.vote.api.VoteProvider;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.model.Vote;
import de.jexcellence.vote.service.VoteLeaderboardService;
import de.jexcellence.vote.service.VotePartyService;
import de.jexcellence.vote.service.VoteService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of the VoteProvider API interface.
 * Provides external access to vote submission and statistics.
 */
public class VoteProviderImpl implements VoteProvider {

    private final VoteService voteService;
    private final VoteLeaderboardService leaderboardService;
    private final @Nullable VotePartyService votePartyService;
    private final boolean writeHooksEnabled;

    /**
     * Creates a new VoteProviderImpl.
     *
     * @param voteService        the vote service for processing votes
     * @param leaderboardService the leaderboard service for rankings
     * @param votePartyService   the vote-party service, or {@code null} when disabled
     * @param writeHooksEnabled  whether the Premium write-hooks are active on this edition
     */
    public VoteProviderImpl(@NotNull VoteService voteService,
                            @NotNull VoteLeaderboardService leaderboardService,
                            @Nullable VotePartyService votePartyService,
                            boolean writeHooksEnabled) {
        this.voteService = voteService;
        this.leaderboardService = leaderboardService;
        this.votePartyService = votePartyService;
        this.writeHooksEnabled = writeHooksEnabled;
    }

    @Override
    public int[] getVotePartyProgress() {
        return voteService.votePartyProgress();
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

    @Override
    public @NotNull CompletableFuture<VoteSnapshot> getSnapshot(@NotNull UUID playerUuid) {
        return voteService.getPlayerStats(playerUuid);
    }

    @Override
    public @NotNull CompletableFuture<Integer> getHighestStreak(@NotNull UUID playerUuid) {
        return voteService.getPlayerStats(playerUuid).thenApply(VoteSnapshot::highestStreak);
    }

    @Override
    public @NotNull List<String> listServices() {
        return voteService.serviceNames();
    }

    @Override
    public @NotNull CompletableFuture<Boolean> canVoteNow(@NotNull UUID playerUuid,
                                                          @NotNull String serviceName) {
        return voteService.secondsUntilNextVote(playerUuid, serviceName).thenApply(seconds -> seconds <= 0L);
    }

    @Override
    public @NotNull CompletableFuture<Instant> nextVoteAt(@NotNull UUID playerUuid,
                                                          @NotNull String serviceName) {
        return voteService.secondsUntilNextVote(playerUuid, serviceName)
                .thenApply(seconds -> Instant.now().plusSeconds(Math.max(0L, seconds)));
    }

    @Override
    public @NotNull CompletableFuture<Integer> getRank(@NotNull UUID playerUuid) {
        return voteService.getAllTimeRank(playerUuid);
    }

    @Override
    public @NotNull CompletableFuture<Integer> getMonthlyRank(@NotNull UUID playerUuid) {
        return voteService.getMonthlyRank(playerUuid);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> grantVotePoints(@NotNull UUID playerUuid, int amount,
                                                               @NotNull String reason) {
        if (!writeHooksEnabled) {
            return CompletableFuture.completedFuture(false);
        }
        return voteService.grantVotePoints(playerUuid, amount, reason);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> forceStreakGrace(@NotNull UUID playerUuid) {
        if (!writeHooksEnabled) {
            return CompletableFuture.completedFuture(false);
        }
        return voteService.forceStreakGrace(playerUuid);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> triggerVoteParty() {
        VotePartyService party = this.votePartyService;
        if (!writeHooksEnabled || party == null) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.supplyAsync(party::forceComplete);
    }
}
