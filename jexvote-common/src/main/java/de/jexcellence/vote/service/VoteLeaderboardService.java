package de.jexcellence.vote.service;

import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class VoteLeaderboardService {

    private static final int DEFAULT_SIZE = 10;
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final VotePlayerRepository playerRepository;

    private final AtomicReference<CachedBoard> cachedAllTime = new AtomicReference<>();
    private final AtomicReference<CachedBoard> cachedMonthly = new AtomicReference<>();

    public VoteLeaderboardService(@NotNull VotePlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    public @NotNull CompletableFuture<List<VoteSnapshot>> getAllTimeTop(int limit) {
        int fetchSize = Math.max(limit, DEFAULT_SIZE);
        CachedBoard cached = cachedAllTime.get();
        if (cached != null && !cached.isExpired() && cached.entries.size() >= limit) {
            return CompletableFuture.completedFuture(
                    cached.entries.subList(0, Math.min(limit, cached.entries.size())));
        }

        return playerRepository.findTopByTotalVotesAsync(fetchSize)
                .thenApply(entities -> {
                    List<VoteSnapshot> snapshots = entities.stream()
                            .map(e -> new VoteSnapshot(
                                    e.getPlayerUuid(), e.getPlayerName(),
                                    e.getTotalVotes(), e.getMonthlyVotes(),
                                    e.getCurrentStreak(), e.getHighestStreak(),
                                    e.getVotePoints(), e.getLastVoteAt()))
                            .toList();
                    cachedAllTime.set(new CachedBoard(snapshots));
                    return snapshots.subList(0, Math.min(limit, snapshots.size()));
                });
    }

    public @NotNull CompletableFuture<List<VoteSnapshot>> getMonthlyTop(int limit) {
        int fetchSize = Math.max(limit, DEFAULT_SIZE);
        CachedBoard cached = cachedMonthly.get();
        if (cached != null && !cached.isExpired() && cached.entries.size() >= limit) {
            return CompletableFuture.completedFuture(
                    cached.entries.subList(0, Math.min(limit, cached.entries.size())));
        }

        return playerRepository.findTopByMonthlyVotesAsync(fetchSize)
                .thenApply(entities -> {
                    List<VoteSnapshot> snapshots = entities.stream()
                            .map(e -> new VoteSnapshot(
                                    e.getPlayerUuid(), e.getPlayerName(),
                                    e.getTotalVotes(), e.getMonthlyVotes(),
                                    e.getCurrentStreak(), e.getHighestStreak(),
                                    e.getVotePoints(), e.getLastVoteAt()))
                            .toList();
                    cachedMonthly.set(new CachedBoard(snapshots));
                    return snapshots.subList(0, Math.min(limit, snapshots.size()));
                });
    }

    public void invalidateCache() {
        cachedAllTime.set(null);
        cachedMonthly.set(null);
    }

    private record CachedBoard(List<VoteSnapshot> entries, Instant fetchedAt) {
        CachedBoard(List<VoteSnapshot> entries) {
            this(entries, Instant.now());
        }
        boolean isExpired() {
            return Duration.between(fetchedAt, Instant.now()).compareTo(CACHE_TTL) > 0;
        }
    }
}
