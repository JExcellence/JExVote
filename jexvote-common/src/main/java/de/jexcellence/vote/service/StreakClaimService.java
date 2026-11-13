package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.database.repository.ClaimedStreakRewardRepository;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles manual claiming of streak milestone rewards.
 * When {@code claim-mode: manual} is configured, streak rewards
 * are not auto-granted at vote time - instead players claim them
 * from the streak GUI.
 */
public class StreakClaimService {

    /**
     * Result of a claim attempt.
     */
    public enum ClaimResult {
        SUCCESS,
        ALREADY_CLAIMED,
        NOT_REACHED,
        UNKNOWN_MILESTONE,
        GRANT_FAILED,
        /** Claimed, but some rewards could not be paid; they are logged for the team. */
        PARTIAL,
        /** The same milestone is already being claimed by this player; nothing was granted. */
        BUSY
    }

    private static final String MIGRATION_MARKER = "streak-claim-migrated.done";

    private final Logger logger;
    private final ClaimedStreakRewardRepository claimedRepository;
    private final VotePlayerRepository playerRepository;
    private final VoteRewardService rewardService;
    private final PlayerRewardChain.PlayerThread playerTasks;
    private final Executor asyncTasks;
    private final File dataFolder;
    private final Set<String> claimsInFlight = ConcurrentHashMap.newKeySet();
    private final UnsavedClaimStore unsavedClaims;
    private final Set<String> savesInFlight = ConcurrentHashMap.newKeySet();

    public StreakClaimService(@NotNull JavaPlugin plugin,
                              @NotNull ClaimedStreakRewardRepository claimedRepository,
                              @NotNull VotePlayerRepository playerRepository,
                              @NotNull VoteRewardService rewardService) {
        this(plugin.getLogger(), plugin.getDataFolder(), claimedRepository, playerRepository, rewardService,
                PlatformScheduler.of(plugin));
    }

    private StreakClaimService(@NotNull Logger logger, @NotNull File dataFolder,
                               @NotNull ClaimedStreakRewardRepository claimedRepository,
                               @NotNull VotePlayerRepository playerRepository,
                               @NotNull VoteRewardService rewardService, @NotNull PlatformScheduler scheduler) {
        this(logger, dataFolder, claimedRepository, playerRepository, rewardService, scheduler::runAtEntity,
                scheduler::runAsync);
    }

    StreakClaimService(@NotNull Logger logger, @NotNull File dataFolder,
                       @NotNull ClaimedStreakRewardRepository claimedRepository,
                       @NotNull VotePlayerRepository playerRepository, @NotNull VoteRewardService rewardService,
                       @NotNull PlayerRewardChain.PlayerThread playerTasks, @NotNull Executor asyncTasks) {
        this.logger = logger;
        this.claimedRepository = claimedRepository;
        this.playerRepository = playerRepository;
        this.rewardService = rewardService;
        this.playerTasks = playerTasks;
        this.asyncTasks = asyncTasks;
        this.dataFolder = dataFolder;
        this.unsavedClaims = new UnsavedClaimStore(dataFolder, logger);
    }

    /**
     * Saves the claim records of days that were paid but could not be recorded earlier, off the calling thread. Until
     * a record is saved its day stays closed, so it cannot be paid twice.
     */
    public void retryUnsavedClaims() {
        List<UnsavedClaimStore.UnsavedClaim> pending = unsavedClaims.all();
        if (pending.isEmpty()) {
            return;
        }
        asyncTasks.execute(() -> pending.forEach(this::saveUnsavedClaim));
    }

    private void retrySaveLater(@NotNull UUID uuid, int milestoneDay) {
        String key = UnsavedClaimStore.key(uuid, milestoneDay);
        if (!savesInFlight.add(key)) {
            return;
        }
        try {
            unsavedClaims.find(uuid, milestoneDay).ifPresentOrElse(
                    claim -> asyncTasks.execute(() -> {
                        try {
                            saveUnsavedClaim(claim);
                        } finally {
                            savesInFlight.remove(key);
                        }
                    }),
                    () -> savesInFlight.remove(key));
        } catch (Exception ex) {
            savesInFlight.remove(key);
            logger.log(Level.WARNING, ex, () -> String.format(
                    "Could not schedule the save of streak claim day %d of %s", milestoneDay, uuid));
        }
    }

    private void saveUnsavedClaim(@NotNull UnsavedClaimStore.UnsavedClaim claim) {
        UUID uuid = claim.player();
        int milestoneDay = claim.day();
        try {
            claimedRepository.createClaim(uuid, milestoneDay, claim.auto());
            unsavedClaims.remove(uuid, milestoneDay);
            logger.log(Level.INFO, () -> String.format(
                    "Saved the delayed streak claim day %d of %s", milestoneDay, uuid));
        } catch (Exception ex) {
            logger.log(Level.WARNING, ex, () -> String.format(
                    "Streak claim day %d of %s still could not be saved - the day stays closed", milestoneDay, uuid));
        }
    }

    /**
     * Returns the set of milestone day numbers the player has already claimed.
     */
    public @NotNull CompletableFuture<Set<Integer>> getClaimedDays(@NotNull UUID uuid) {
        return claimedRepository.findClaimedDays(uuid);
    }

    /**
     * Attempts to claim a streak milestone reward for the given player.
     * Uses {@code highestStreak} so rewards remain claimable even after
     * a streak resets. A day that was paid but whose claim could not be saved answers {@code ALREADY_CLAIMED} and
     * retries the save.
     */
    public @NotNull CompletableFuture<ClaimResult> claimMilestone(@NotNull Player player, int milestoneDay) {
        if (!rewardService.getStreakRewards().containsKey(milestoneDay)) {
            return CompletableFuture.completedFuture(ClaimResult.UNKNOWN_MILESTONE);
        }
        String claimKey = UnsavedClaimStore.key(player.getUniqueId(), milestoneDay);
        if (!claimsInFlight.add(claimKey)) {
            return CompletableFuture.completedFuture(ClaimResult.BUSY);
        }
        CompletableFuture<ClaimResult> claim;
        try {
            claim = claimOnce(player, milestoneDay);
        } catch (Exception ex) {
            claim = CompletableFuture.failedFuture(ex);
        }
        return claim.whenComplete((result, ex) -> claimsInFlight.remove(claimKey));
    }

    /**
     * Checks and grants one milestone. {@link #claimMilestone} lets only one call per player and day run at a
     * time, so a double click cannot pass the "not claimed yet" check twice and pay out twice.
     */
    private @NotNull CompletableFuture<ClaimResult> claimOnce(@NotNull Player player, int milestoneDay) {
        UUID uuid = player.getUniqueId();

        if (unsavedClaims.contains(uuid, milestoneDay)) {
            retrySaveLater(uuid, milestoneDay);
            return CompletableFuture.completedFuture(ClaimResult.ALREADY_CLAIMED);
        }
        return claimedRepository.findClaimedDays(uuid).thenCompose(claimed -> {
            if (claimed.contains(milestoneDay)) {
                return CompletableFuture.completedFuture(ClaimResult.ALREADY_CLAIMED);
            }

            return playerRepository.findByUuidAsync(uuid).thenCompose(optPlayer -> {
                if (optPlayer.isEmpty() || optPlayer.get().getHighestStreak() < milestoneDay) {
                    return CompletableFuture.completedFuture(ClaimResult.NOT_REACHED);
                }

                CompletableFuture<ClaimResult> resultFuture = new CompletableFuture<>();
                playerTasks.run(player,
                        () -> grantMilestone(player, milestoneDay, resultFuture),
                        () -> resultFuture.complete(ClaimResult.GRANT_FAILED));
                return resultFuture;
            });
        });
    }

    /**
     * Grants one milestone and records the claim as soon as anything arrived, so a later failed reward can never make
     * the earlier ones claimable twice. Rewards the player left before receiving are kept for the next join and
     * count as delivered.
     */
    private void grantMilestone(@NotNull Player player, int milestoneDay,
                                @NotNull CompletableFuture<ClaimResult> resultFuture) {
        CompletableFuture<VoteRewardService.GrantTally> grant;
        try {
            grant = rewardService.grantStreakReward(player, milestoneDay);
        } catch (Exception ex) {
            grant = CompletableFuture.failedFuture(ex);
        }
        grant.whenComplete((tally, ex) -> {
            if (ex != null || tally == null || tally.granted() == 0) {
                if (ex != null) {
                    logger.log(Level.WARNING, ex, () -> String.format(
                            "Failed to claim streak reward (day %d) for %s", milestoneDay, player.getName()));
                }
                resultFuture.complete(ClaimResult.GRANT_FAILED);
                return;
            }
            ClaimResult result = tally.failed() == 0 ? ClaimResult.SUCCESS : ClaimResult.PARTIAL;
            recordClaimAsync(player.getUniqueId(), milestoneDay, result, resultFuture);
        });
    }

    private void recordClaimAsync(@NotNull UUID uuid, int milestoneDay, @NotNull ClaimResult result,
                                  @NotNull CompletableFuture<ClaimResult> resultFuture) {
        try {
            asyncTasks.execute(() -> recordClaim(uuid, milestoneDay, result, resultFuture));
        } catch (Exception ex) {
            reportUnrecorded(uuid, milestoneDay, false, ex);
            resultFuture.complete(result);
        }
    }

    /**
     * Records the claim. The rewards already arrived, so the result stands even if recording failed; the day is then
     * kept closed in {@code unsaved-streak-claims.txt} and the save is retried on the next start and the next click.
     */
    private void recordClaim(@NotNull UUID uuid, int milestoneDay, @NotNull ClaimResult result,
                             @NotNull CompletableFuture<ClaimResult> resultFuture) {
        try {
            claimedRepository.createClaim(uuid, milestoneDay, false);
        } catch (Exception ex) {
            reportUnrecorded(uuid, milestoneDay, false, ex);
        } finally {
            resultFuture.complete(result);
        }
    }

    private void reportUnrecorded(@NotNull UUID uuid, int milestoneDay, boolean autoClaimed,
                                  @NotNull Exception failure) {
        unsavedClaims.add(uuid, milestoneDay, autoClaimed);
        logger.log(Level.SEVERE, failure, () -> String.format("Streak reward day %d of %s was paid but the claim "
                + "could not be recorded - the day stays closed and the save is retried", milestoneDay, uuid));
    }


    /**
     * One-time migration: marks all milestones ≤ {@code highestStreak} as
     * auto-claimed for every existing player. Idempotent - skips already-claimed rows. A marker file in the data
     * folder makes it run once; a row that could not be saved is logged, the others still run, and the marker is
     * withheld so the migration runs again next start.
     */
    public void runMigration() {
        if (migrationMarker().exists()) {
            return;
        }
        logger.info("Running streak claim migration for existing players...");
        Map<Integer, ?> milestones = rewardService.getStreakRewards();
        if (milestones.isEmpty()) {
            logger.info("No streak milestones configured - migration skipped.");
            return;
        }

        playerRepository.findAllAsync().thenAccept(players -> {
            int migrated = 0;
            int failed = 0;
            for (var player : players) {
                MigrationCount outcome = migratePlayer(player.getPlayerUuid(), player.getHighestStreak(),
                        milestones.keySet());
                migrated += outcome.saved();
                failed += outcome.failed();
            }
            final int count = migrated;
            final int failures = failed;
            if (failures > 0) {
                logger.log(Level.SEVERE, () -> String.format("Streak claim migration saved %d claim record(s), "
                        + "%d failed - it runs again on the next start", count, failures));
                return;
            }
            writeMigrationMarker();
            logger.log(Level.INFO, () -> String.format(
                    "Streak claim migration complete - %d claim record(s) created", count));
        }).exceptionally(ex -> {
            logger.log(Level.SEVERE, "Streak claim migration failed", ex);
            return null;
        });
    }

    /** How many auto-claims of one player the migration saved and how many failed. */
    private record MigrationCount(int saved, int failed) {
    }

    /** Saves the missing auto-claims of one player. */
    private @NotNull MigrationCount migratePlayer(@NotNull UUID uuid, int highest,
                                                 @NotNull Set<Integer> milestoneDays) {
        int saved = 0;
        int failed = 0;
        if (highest <= 0) {
            return new MigrationCount(saved, failed);
        }
        Set<Integer> alreadyClaimed = claimedRepository.findClaimedDays(uuid).join();
        for (int day : milestoneDays) {
            if (day > highest || alreadyClaimed.contains(day)) {
                continue;
            }
            try {
                claimedRepository.createClaim(uuid, day, true);
                saved++;
            } catch (Exception ex) {
                failed++;
                logger.log(Level.WARNING, ex, () -> String.format(
                        "Streak claim migration could not save day %d of %s", day, uuid));
            }
        }
        return new MigrationCount(saved, failed);
    }

    private @NotNull File migrationMarker() {
        return new File(dataFolder, MIGRATION_MARKER);
    }

    /** Writes the run-once marker after every claim row was saved. Delete the file to run the migration again. */
    private void writeMigrationMarker() {
        try {
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                logger.warning("[streak-migration] could not create data folder for the marker");
                return;
            }
            Files.writeString(migrationMarker().toPath(),
                    "Streak-claim migration done. Delete this file to re-run it.\n");
        } catch (IOException ex) {
            logger.log(Level.WARNING, ex,
                    () -> "[streak-migration] ran but the marker could not be written (may re-run next start)");
        }
    }

    /**
     * Marks a streak milestone as auto-claimed (used in auto mode to track
     * which milestones have been granted, even when auto-granting).
     */
    public void markAutoClaimed(@NotNull UUID uuid, int milestoneDay) {
        try {
            claimedRepository.createClaim(uuid, milestoneDay, true);
        } catch (Exception ex) {
            reportUnrecorded(uuid, milestoneDay, true, ex);
        }
    }
}
