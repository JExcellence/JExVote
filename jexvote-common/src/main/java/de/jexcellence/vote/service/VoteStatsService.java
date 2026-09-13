package de.jexcellence.vote.service;

import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.database.entity.VotePlayerEntity;
import de.jexcellence.vote.database.entity.VoteRecordEntity;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import de.jexcellence.vote.database.repository.VoteRecordRepository;
import de.jexcellence.vote.model.VoteSite;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The read/query surface of the vote system - snapshots, ranks, per-service cooldowns and
 * site lookups. Extracted from {@link VoteService} (which delegates to it) so the reads live
 * in one cohesive, side-effect-free, unit-testable collaborator apart from the vote-processing
 * pipeline. Shares the live {@code voteSites} reference with {@link VoteService}, so a reload
 * that updates it is seen here too.
 *
 * @author JExcellence
 */
public final class VoteStatsService {

    private final VotePlayerRepository playerRepository;
    private final VoteRecordRepository recordRepository;
    private final AtomicReference<Map<String, VoteSite>> voteSites;

    public VoteStatsService(@NotNull VotePlayerRepository playerRepository,
                            @NotNull VoteRecordRepository recordRepository,
                            @NotNull AtomicReference<Map<String, VoteSite>> voteSites) {
        this.playerRepository = playerRepository;
        this.recordRepository = recordRepository;
        this.voteSites = voteSites;
    }

    public @NotNull CompletableFuture<VoteSnapshot> getPlayerStats(@NotNull UUID uuid) {
        return playerRepository.findByUuidAsync(uuid).thenApply(opt ->
                opt.map(this::toSnapshot).orElse(
                        new VoteSnapshot(uuid, null, 0, 0, 0, 0, 0, null)));
    }

    public @NotNull VoteSnapshot toSnapshot(@NotNull VotePlayerEntity entity) {
        return new VoteSnapshot(
                entity.getPlayerUuid(),
                entity.getPlayerName(),
                entity.getTotalVotes(),
                entity.getMonthlyVotes(),
                entity.getCurrentStreak(),
                entity.getHighestStreak(),
                entity.getVotePoints(),
                entity.getLastVoteAt());
    }

    public @NotNull Map<String, VoteSite> getVoteSites() {
        return voteSites.get();
    }

    /** The configured vote-site service names (sync, cached). */
    public @NotNull List<String> serviceNames() {
        return voteSites.get().values().stream().map(VoteSite::serviceName).toList();
    }

    public @Nullable VoteSite findSiteByServiceName(@NotNull String serviceName) {
        // Locale.ROOT avoids the Turkish-locale "i" bug: on a tr/az server "I".toLowerCase()
        // yields 'ı' (dotless i), so a service-name match without ROOT can silently fail.
        String lower = serviceName.toLowerCase(Locale.ROOT);
        return voteSites.get().values().stream()
                .filter(site -> site.serviceName().toLowerCase(Locale.ROOT).equals(lower)
                        || site.id().toLowerCase(Locale.ROOT).equals(lower))
                .findFirst()
                .orElse(null);
    }

    /**
     * Seconds until {@code uuid} may vote again on {@code serviceName} (0 = ready now, also for
     * an unknown service). Honours the site's rolling / daily-reset cooldown.
     */
    public @NotNull CompletableFuture<Long> secondsUntilNextVote(@NotNull UUID uuid,
                                                                 @NotNull String serviceName) {
        VoteSite site = findSiteByServiceName(serviceName);
        if (site == null) {
            return CompletableFuture.completedFuture(0L);
        }
        return voteCooldownsSeconds(uuid).thenApply(remaining ->
                remaining.getOrDefault(site.serviceName(), 0L));
    }

    /** All-time vote rank (1-based), or {@code -1} if the player has no vote profile. */
    public @NotNull CompletableFuture<Integer> getAllTimeRank(@NotNull UUID uuid) {
        return playerRepository.findByUuidAsync(uuid).thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(-1);
            }
            return playerRepository.countWithMoreTotalVotesAsync(opt.orElseThrow().getTotalVotes())
                    .thenApply(count -> count.intValue() + 1);
        });
    }

    /** Monthly vote rank (1-based), or {@code -1} if the player has no vote profile. */
    public @NotNull CompletableFuture<Integer> getMonthlyRank(@NotNull UUID uuid) {
        return playerRepository.findByUuidAsync(uuid).thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(-1);
            }
            return playerRepository.countWithMoreMonthlyVotesAsync(opt.orElseThrow().getMonthlyVotes())
                    .thenApply(count -> count.intValue() + 1);
        });
    }

    /**
     * Every distinct service name that has actually been <b>received</b> in a vote (as stored,
     * un-normalised) → its most recent epoch-seconds. Used by the service diagnostics command to
     * spot names that match no configured site's {@code service-name}.
     */
    public @NotNull CompletableFuture<Map<String, Long>> receivedServiceNames() {
        return recordRepository.findAllAsync().thenApply(records -> {
            Map<String, Long> out = new HashMap<>();
            for (VoteRecordEntity entry : records) {
                if (entry.getServiceName() == null || entry.getVotedAt() == null) {
                    continue;
                }
                out.merge(entry.getServiceName(), entry.getVotedAt().getEpochSecond(), Math::max);
            }
            return out;
        });
    }

    public @NotNull CompletableFuture<Map<String, Long>> voteCooldownsSeconds(@NotNull UUID uuid) {
        Map<String, VoteSite> sites = voteSites.get();
        return recordRepository.findByPlayer(uuid).thenApply(records -> {
            // Key by lowercased service name so a casing/whitespace mismatch between the recorded
            // vote and the configured site (a common Votifier gotcha) still maps to its site.
            Map<String, Long> latestEpoch = new HashMap<>();
            for (VoteRecordEntity entry : records) {
                if (entry.getServiceName() == null || entry.getVotedAt() == null) {
                    continue;
                }
                latestEpoch.merge(entry.getServiceName().trim().toLowerCase(Locale.ROOT),
                        entry.getVotedAt().getEpochSecond(), Math::max);
            }
            Map<String, Long> remaining = new HashMap<>();
            for (VoteSite site : sites.values()) {
                long lastEpoch = latestEpoch.getOrDefault(
                        site.serviceName().trim().toLowerCase(Locale.ROOT), 0L);
                remaining.put(site.serviceName(), site.secondsUntilNextVote(lastEpoch));
            }
            return remaining;
        });
    }
}
