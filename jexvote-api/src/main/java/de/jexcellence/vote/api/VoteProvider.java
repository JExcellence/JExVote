package de.jexcellence.vote.api;

import de.jexcellence.vote.api.model.VoteSnapshot;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
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
     * The player's full vote stats in one call (totals, streaks, points, last vote).
     * Never null - an unknown player resolves to a zeroed snapshot. Replaces composing
     * the individual getters.
     */
    @NotNull CompletableFuture<VoteSnapshot> getSnapshot(@NotNull UUID playerUuid);

    /** The player's highest-ever vote streak. */
    @NotNull CompletableFuture<Integer> getHighestStreak(@NotNull UUID playerUuid);

    /** The configured vote-site service names (synchronous - reads cached config). */
    @NotNull List<String> listServices();

    /**
     * Whether the player may vote on {@code serviceName} right now (cooldown elapsed).
     * An unknown service resolves to {@code true}.
     */
    @NotNull CompletableFuture<Boolean> canVoteNow(@NotNull UUID playerUuid, @NotNull String serviceName);

    /**
     * The instant the player may next vote on {@code serviceName} - {@code Instant.now()}
     * (or earlier) when ready now.
     */
    @NotNull CompletableFuture<Instant> nextVoteAt(@NotNull UUID playerUuid, @NotNull String serviceName);

    /** The player's all-time vote rank (1-based), or {@code -1} when they have no profile. */
    @NotNull CompletableFuture<Integer> getRank(@NotNull UUID playerUuid);

    /** The player's monthly vote rank (1-based), or {@code -1} when they have no profile. */
    @NotNull CompletableFuture<Integer> getMonthlyRank(@NotNull UUID playerUuid);

    // ── Write hooks (Premium-gated at runtime; see JExVoteAPI.supports("write-hooks")) ──

    /**
     * Grants {@code amount} vote-points to a (possibly offline) player, attributed to
     * {@code reason} in the log. Resolves to {@code false} on the Free edition (feature
     * off) or when the player has no vote profile.
     */
    @NotNull CompletableFuture<Boolean> grantVotePoints(@NotNull UUID playerUuid, int amount,
                                                        @NotNull String reason);

    /**
     * Grants one Streak Freeze to a player (programmatic streak grace; no cost, ignores
     * the owned cap). Resolves to {@code false} on the Free edition or when the player
     * has no vote profile.
     */
    @NotNull CompletableFuture<Boolean> forceStreakGrace(@NotNull UUID playerUuid);

    /**
     * Force-completes the current vote party now (admin/automation). Resolves to
     * {@code false} on the Free edition or when the vote-party feature is disabled.
     */
    @NotNull CompletableFuture<Boolean> triggerVoteParty();

    /**
     * Live vote-party progress as {@code {current, target}}. Returns {@code {0, 0}}
     * when no party is active or the feature is disabled. Synchronous - reads an
     * in-memory counter, so it's safe to call from a render/tick.
     */
    default int[] getVotePartyProgress() {
        return new int[]{0, 0};
    }
}
