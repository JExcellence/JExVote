package de.jexcellence.vote.service;

import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
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
        CachedBoard cached = cachedAllTime.get();
        if (cached != null && !cached.isExpired() && cached.size >= limit) {
            return CompletableFuture.completedFuture(cached.entries.subList(0, Math.min(limit, cached.entries.size())));
        }

        return playerRepository.findAllAsync()
                .thenApply(entities -> {
                    List<VoteSnapshot> snapshots = entities.stream()
                            .sorted((a, b) -> Integer.compare(b.getTotalVotes(), a.getTotalVotes()))
                            .limit(Math.max(limit, DEFAULT_SIZE))
                            .map(e -> new VoteSnapshot(
                                    e.getPlayerUuid(), e.getPlayerName(),
                                    e.getTotalVotes(), e.getMonthlyVotes(),
                                    e.getCurrentStreak(), e.getHighestStreak(),
                                    e.getVotePoints(), e.getLastVoteAt()))
                            .toList();
                    cachedAllTime.set(new CachedBoard(snapshots, limit));
                    return snapshots.subList(0, Math.min(limit, snapshots.size()));
                });
    }

    public @NotNull CompletableFuture<List<VoteSnapshot>> getMonthlyTop(int limit) {
        CachedBoard cached = cachedMonthly.get();
        if (cached != null && !cached.isExpired() && cached.size >= limit) {
            return CompletableFuture.completedFuture(cached.entries.subList(0, Math.min(limit, cached.entries.size())));
        }

        return playerRepository.findAllAsync()
                .thenApply(entities -> {
                    List<VoteSnapshot> snapshots = entities.stream()
                            .sorted((a, b) -> Integer.compare(b.getMonthlyVotes(), a.getMonthlyVotes()))
                            .limit(Math.max(limit, DEFAULT_SIZE))
                            .map(e -> new VoteSnapshot(
                                    e.getPlayerUuid(), e.getPlayerName(),
                                    e.getTotalVotes(), e.getMonthlyVotes(),
                                    e.getCurrentStreak(), e.getHighestStreak(),
                                    e.getVotePoints(), e.getLastVoteAt()))
                            .toList();
                    cachedMonthly.set(new CachedBoard(snapshots, limit));
                    return snapshots.subList(0, Math.min(limit, snapshots.size()));
                });
    }

    public void invalidateCache() {
        cachedAllTime.set(null);
        cachedMonthly.set(null);
    }

    private record CachedBoard(List<VoteSnapshot> entries, int size, Instant fetchedAt) {
        CachedBoard(List<VoteSnapshot> entries, int size) {
            this(entries, size, Instant.now());
        }
        boolean isExpired() {
            return Duration.between(fetchedAt, Instant.now()).compareTo(CACHE_TTL) > 0;
        }
    }
}
