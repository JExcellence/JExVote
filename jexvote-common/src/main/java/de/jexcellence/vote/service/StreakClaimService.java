package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.database.entity.ClaimedStreakRewardEntity;
import de.jexcellence.vote.database.repository.ClaimedStreakRewardRepository;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles manual claiming of streak milestone rewards.
 * When {@code claim-mode: manual} is configured, streak rewards
 * are not auto-granted at vote time — instead players claim them
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
        GRANT_FAILED
    }

    private static final String MIGRATION_MARKER = "streak-claim-migrated.done";

    private final Logger logger;
    private final ClaimedStreakRewardRepository claimedRepository;
    private final VotePlayerRepository playerRepository;
    private final VoteRewardService rewardService;
    private final PlatformScheduler scheduler;
    private final File dataFolder;

    public StreakClaimService(@NotNull JavaPlugin plugin,
                              @NotNull ClaimedStreakRewardRepository claimedRepository,
                              @NotNull VotePlayerRepository playerRepository,
                              @NotNull VoteRewardService rewardService) {
        this.logger = plugin.getLogger();
        this.claimedRepository = claimedRepository;
        this.playerRepository = playerRepository;
        this.rewardService = rewardService;
        this.scheduler = PlatformScheduler.of(plugin);
        this.dataFolder = plugin.getDataFolder();
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
     * a streak resets.
     */
    public @NotNull CompletableFuture<ClaimResult> claimMilestone(@NotNull Player player, int milestoneDay) {
        if (!rewardService.getStreakRewards().containsKey(milestoneDay)) {
            return CompletableFuture.completedFuture(ClaimResult.UNKNOWN_MILESTONE);
        }

        UUID uuid = player.getUniqueId();

        return claimedRepository.findClaimedDays(uuid).thenCompose(claimed -> {
            if (claimed.contains(milestoneDay)) {
                return CompletableFuture.completedFuture(ClaimResult.ALREADY_CLAIMED);
            }

            return playerRepository.findByUuidAsync(uuid).thenCompose(optPlayer -> {
                if (optPlayer.isEmpty() || optPlayer.get().getHighestStreak() < milestoneDay) {
                    return CompletableFuture.completedFuture(ClaimResult.NOT_REACHED);
                }

                // Grant rewards on main thread via scheduler
                CompletableFuture<ClaimResult> resultFuture = new CompletableFuture<>();
                scheduler.runAtEntity(player, () ->
                        rewardService.grantStreakReward(player, milestoneDay).thenAccept(success -> {
                            if (Boolean.TRUE.equals(success)) {
                                claimedRepository.createClaim(uuid, milestoneDay, false);
                                resultFuture.complete(ClaimResult.SUCCESS);
                            } else {
                                resultFuture.complete(ClaimResult.GRANT_FAILED);
                            }
                        }).exceptionally(ex -> {
                            logger.log(Level.WARNING, ex, () -> String.format(
                                    "Failed to claim streak reward (day %d) for %s",
                                    milestoneDay, player.getName()));
                            resultFuture.complete(ClaimResult.GRANT_FAILED);
                            return null;
                        }));
                return resultFuture;
            });
        });
    }

    /**
     * One-time migration: marks all milestones ≤ {@code highestStreak} as
     * auto-claimed for every existing player. Idempotent — skips already-claimed rows.
     */
    public void runMigration() {
        // Run-once guard: a marker file in the data folder. Without it the
        // migration re-ran every startup and re-inserted existing rows, throwing
        // unique-constraint (23505) warnings on jexvote_claimed_streaks.
        if (migrationMarker().exists()) {
            return;
        }
        logger.info("Running streak claim migration for existing players...");
        Map<Integer, ?> milestones = rewardService.getStreakRewards();
        if (milestones.isEmpty()) {
            logger.info("No streak milestones configured — migration skipped.");
            return;
        }

        playerRepository.findAllAsync().thenAccept(players -> {
            int migrated = 0;
            for (var player : players) {
                int highest = player.getHighestStreak();
                if (highest <= 0) continue;

                // Idempotent: only create claims for milestones the player hasn't
                // already got — never blindly re-insert (avoids the 23505 violation).
                Set<Integer> alreadyClaimed = claimedRepository.findClaimedDays(player.getPlayerUuid()).join();
                for (int day : milestones.keySet()) {
                    if (day <= highest && !alreadyClaimed.contains(day)) {
                        claimedRepository.createClaim(player.getPlayerUuid(), day, true);
                        migrated++;
                    }
                }
            }

            writeMigrationMarker();
            final int count = migrated;
            logger.log(Level.INFO, () -> String.format(
                    "Streak claim migration complete — %d claim record(s) created", count));
        }).exceptionally(ex -> {
            logger.log(Level.SEVERE, "Streak claim migration failed", ex);
            return null;
        });
    }

    private @NotNull File migrationMarker() {
        return new File(dataFolder, MIGRATION_MARKER);
    }

    /** Writes the run-once marker so the migration never repeats (idempotent commands aside). */
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
        claimedRepository.createClaim(uuid, milestoneDay, true);
    }
}
