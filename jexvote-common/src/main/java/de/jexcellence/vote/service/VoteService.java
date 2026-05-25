package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.api.event.VoteReceivedEvent;
import de.jexcellence.vote.api.event.VoteRewardClaimedEvent;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.database.entity.PendingVoteRewardEntity;
import de.jexcellence.vote.database.entity.VotePlayerEntity;
import de.jexcellence.vote.database.entity.VoteRecordEntity;
import de.jexcellence.vote.database.repository.PendingVoteRewardRepository;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import de.jexcellence.vote.database.repository.VoteRecordRepository;
import de.jexcellence.vote.model.Vote;
import de.jexcellence.vote.model.VoteSite;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VoteService {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final PlatformScheduler scheduler;
    private final VotePlayerRepository playerRepository;
    private final VoteRecordRepository recordRepository;
    private final PendingVoteRewardRepository pendingRewardRepository;
    private final VoteRewardService rewardService;
    private final VoteBroadcastService broadcastService;

    private final AtomicReference<Map<String, VoteSite>> voteSites;
    // volatile is sufficient: single-write / multi-read
    private volatile Duration streakTimeout;
    private final AtomicReference<Map<Integer, List<String>>> streakCommands;
    // volatile is sufficient: single-write / multi-read
    private volatile int recordRetentionDays;

    // Configuration group — suppressed (S107)
    @SuppressWarnings("java:S107")
    public VoteService(@NotNull JavaPlugin plugin,
                       @NotNull VotePlayerRepository playerRepository,
                       @NotNull VoteRecordRepository recordRepository,
                       @NotNull PendingVoteRewardRepository pendingRewardRepository,
                       @NotNull VoteRewardService rewardService,
                       @NotNull VoteBroadcastService broadcastService,
                       @NotNull Map<String, VoteSite> voteSites,
                       int streakTimeoutHours,
                       @NotNull Map<Integer, List<String>> streakCommands,
                       int recordRetentionDays) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.scheduler = PlatformScheduler.of(plugin);
        this.playerRepository = playerRepository;
        this.recordRepository = recordRepository;
        this.pendingRewardRepository = pendingRewardRepository;
        this.rewardService = rewardService;
        this.broadcastService = broadcastService;
        this.voteSites = new AtomicReference<>(voteSites);
        this.streakTimeout = Duration.ofHours(streakTimeoutHours);
        this.streakCommands = new AtomicReference<>(streakCommands);
        this.recordRetentionDays = recordRetentionDays;
    }

    /**
     * Called by {@code /jexvote reload} to refresh mutable config state.
     */
    public void reload(@NotNull Map<String, VoteSite> voteSites,
                       int streakTimeoutHours,
                       @NotNull Map<Integer, List<String>> streakCommands,
                       int recordRetentionDays,
                       boolean manualStreakClaim) {
        this.voteSites.set(voteSites);
        this.streakTimeout = Duration.ofHours(streakTimeoutHours);
        this.streakCommands.set(streakCommands);
        this.recordRetentionDays = recordRetentionDays;
        this.rewardService.setManualStreakClaim(manualStreakClaim);
    }

    public @NotNull CompletableFuture<Boolean> processVote(@NotNull Vote vote) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info(String.format("Processing vote: %s / %s", vote.username(), vote.serviceName()));

                UUID uuid = resolveUuid(vote.username());
                if (uuid == null) {
                    logger.warning(String.format("Could not resolve UUID for voter: %s — player has never joined this server", vote.username()));
                    return false;
                }

                logger.fine(String.format("Resolved UUID for %s: %s", vote.username(), uuid));

                if (!fireVoteReceivedEvent(vote, uuid)) {
                    logger.info(String.format("Vote for %s was cancelled by event listener", vote.username()));
                    return false;
                }

                VotePlayerEntity player = findOrCreatePlayer(vote, uuid);
                int points = resolvePointsForSite(vote.serviceName());
                applyVoteToPlayer(player, vote, points);

                recordRepository.create(new VoteRecordEntity(
                        uuid, vote.username(), vote.serviceName(),
                        vote.address(), vote.timestamp()));

                deliverOrQueueRewards(vote, uuid, player);
                return true;
            } catch (Exception e) {
                logger.log(Level.SEVERE, e, () -> String.format("Failed to process vote for %s", vote.username()));
                return false;
            }
        });
    }

    private boolean fireVoteReceivedEvent(@NotNull Vote vote, @NotNull UUID uuid) {
        CompletableFuture<Boolean> eventResult = new CompletableFuture<>();
        scheduler.runSync(() -> {
            try {
                VoteReceivedEvent event = new VoteReceivedEvent(
                        vote.username(), uuid, vote.serviceName(), vote.address());
                Bukkit.getPluginManager().callEvent(event);
                eventResult.complete(!event.isCancelled());
            } catch (Exception e) {
                eventResult.completeExceptionally(e);
            }
        });
        return Boolean.TRUE.equals(eventResult.join());
    }

    private @NotNull VotePlayerEntity findOrCreatePlayer(@NotNull Vote vote, @NotNull UUID uuid) {
        VotePlayerEntity player = playerRepository.findByUuid(uuid)
                .orElseGet(() -> {
                    logger.info(String.format("Creating new vote profile for %s (%s)", vote.username(), uuid));
                    VotePlayerEntity newPlayer = new VotePlayerEntity(uuid, vote.username());
                    playerRepository.create(newPlayer);
                    return newPlayer;
                });
        player.setPlayerName(vote.username());
        resetMonthlyIfNeeded(player);
        updateStreak(player);
        return player;
    }

    private int resolvePointsForSite(@NotNull String serviceName) {
        VoteSite site = findSiteByServiceName(serviceName);
        if (site == null) {
            logger.log(Level.WARNING, () -> String.format(
                    "No vote site configured for service '%s' — using default 1 point. Check sites.yml service-name mappings.",
                    serviceName));
            return 1;
        }
        return site.pointsPerVote();
    }

    private void applyVoteToPlayer(@NotNull VotePlayerEntity player, @NotNull Vote vote, int points) {
        player.setTotalVotes(player.getTotalVotes() + 1);
        player.setMonthlyVotes(player.getMonthlyVotes() + 1);
        player.setVotePoints(player.getVotePoints() + points);
        player.setLastVoteAt(vote.timestamp());
        playerRepository.update(player);
    }

    private void deliverOrQueueRewards(@NotNull Vote vote, @NotNull UUID uuid, @NotNull VotePlayerEntity player) {
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        VoteSnapshot snapshot = toSnapshot(player);
        int streak = player.getCurrentStreak();

        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            scheduler.runAtEntity(onlinePlayer, () -> {
                rewardService.grantRewards(onlinePlayer, vote.serviceName(), streak);
                executeStreakCommands(onlinePlayer, vote.serviceName(), streak);
                broadcastService.notifyPlayer(onlinePlayer, vote.serviceName(), streak);
                Bukkit.getPluginManager().callEvent(
                        new VoteRewardClaimedEvent(uuid, vote.serviceName(), snapshot));
            });
            logger.log(Level.INFO, () -> String.format("Vote processed for %s (online) — streak: %d, total: %d",
                    vote.username(), streak, player.getTotalVotes()));
        } else {
            String rewardData = rewardService.serializeRewards(vote.serviceName(), streak);
            if (rewardData != null) {
                pendingRewardRepository.create(
                        new PendingVoteRewardEntity(uuid, vote.serviceName(), rewardData));
            }
            logger.log(Level.INFO, () -> String.format("Vote processed for %s (offline) — rewards queued, streak: %d, total: %d",
                    vote.username(), streak, player.getTotalVotes()));
        }
    }

    public void deliverPendingRewards(@NotNull Player player) {
        pendingRewardRepository.findByPlayer(player.getUniqueId()).thenAccept(pending -> {
            if (pending.isEmpty()) return;

            scheduler.runAtEntity(player, () -> {
                for (PendingVoteRewardEntity reward : pending) {
                    try {
                        rewardService.grantSerializedRewards(player, reward.getRewardData());
                    } catch (Exception e) {
                        logger.log(Level.WARNING,
                                "Failed to deliver pending reward to " + player.getName(), e);
                    }
                }

                // Delete only after successful delivery
                for (PendingVoteRewardEntity reward : pending) {
                    pendingRewardRepository.delete(reward.getId());
                }

                broadcastService.notifyPendingRewards(player, pending.size());
                logger.info("Delivered " + pending.size()
                        + " pending vote reward(s) to " + player.getName());
            });
        });
    }

    public @NotNull CompletableFuture<VoteSnapshot> getPlayerStats(@NotNull UUID uuid) {
        return playerRepository.findByUuidAsync(uuid).thenApply(opt ->
                opt.map(this::toSnapshot).orElse(
                        new VoteSnapshot(uuid, null, 0, 0, 0, 0, 0, null))
        );
    }

    public @NotNull Map<String, VoteSite> getVoteSites() {
        return voteSites.get();
    }

    public @NotNull VoteBroadcastService getBroadcastService() {
        return broadcastService;
    }

    public @Nullable VoteSite findSiteByServiceName(@NotNull String serviceName) {
        String lower = serviceName.toLowerCase();
        return voteSites.get().values().stream()
                .filter(site -> site.serviceName().toLowerCase().equals(lower) ||
                        site.id().toLowerCase().equals(lower))
                .findFirst()
                .orElse(null);
    }

    /**
     * Resets all vote data for a specific player.
     *
     * @param uuid the player UUID to reset
     * @return a future that completes with true if the player was found and reset
     */
    public @NotNull CompletableFuture<Boolean> resetPlayer(@NotNull UUID uuid) {
        return playerRepository.findByUuidAsync(uuid).thenApply(opt -> {
            if (opt.isEmpty()) return false;

            VotePlayerEntity player = opt.orElseThrow();
            player.setTotalVotes(0);
            player.setMonthlyVotes(0);
            player.setCurrentStreak(0);
            player.setHighestStreak(0);
            player.setVotePoints(0);
            player.setLastVoteAt(null);
            player.setMonthlyResetMonth(null);
            playerRepository.update(player);
            return true;
        });
    }

    public void resetAllMonthlyVotes() {
        playerRepository.findAllAsync().thenAccept(players -> {
            String currentMonth = YearMonth.now(ZoneId.systemDefault()).toString();
            for (VotePlayerEntity player : players) {
                player.setMonthlyVotes(0);
                player.setMonthlyResetMonth(currentMonth);
                playerRepository.update(player);
            }
            logger.info("Reset monthly vote counts for " + players.size() + " player(s)");
        }).exceptionally(ex -> {
            logger.log(Level.SEVERE, "Failed to reset monthly votes", ex);
            return null;
        });
    }

    /**
     * Deletes vote records older than the configured retention period.
     * Should be called periodically (e.g. on server start or via scheduler).
     */
    public void purgeOldRecords() {
        if (recordRetentionDays <= 0) return;

        Instant cutoff = Instant.now().minus(Duration.ofDays(recordRetentionDays));
        recordRepository.deleteOlderThan(cutoff).thenAccept(count -> {
            if (count > 0) {
                logger.info("Purged " + count + " vote record(s) older than "
                        + recordRetentionDays + " days");
            }
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "Failed to purge old vote records", ex);
            return null;
        });
    }

    private void updateStreak(@NotNull VotePlayerEntity player) {
        Instant lastVote = player.getLastVoteAt();
        if (lastVote == null || Duration.between(lastVote, Instant.now()).compareTo(streakTimeout) > 0) {
            player.setCurrentStreak(1);
        } else {
            player.setCurrentStreak(player.getCurrentStreak() + 1);
        }

        if (player.getCurrentStreak() > player.getHighestStreak()) {
            player.setHighestStreak(player.getCurrentStreak());
        }
    }

    private void executeStreakCommands(@NotNull Player player, @NotNull String serviceName, int streak) {
        List<String> commands = streakCommands.get().get(streak);
        if (commands == null || commands.isEmpty()) return;

        for (String command : commands) {
            String resolved = command
                    .replace("{player}", player.getName())
                    .replace("{service}", serviceName)
                    .replace("{streak}", String.valueOf(streak));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
        }
    }

    private void resetMonthlyIfNeeded(@NotNull VotePlayerEntity player) {
        String currentMonth = YearMonth.now(ZoneId.systemDefault()).toString();
        if (!currentMonth.equals(player.getMonthlyResetMonth())) {
            player.setMonthlyVotes(0);
            player.setMonthlyResetMonth(currentMonth);
        }
    }

    private @Nullable UUID resolveUuid(@NotNull String username) {
        Player online = Bukkit.getPlayerExact(username);
        if (online != null) return online.getUniqueId();

        OfflinePlayer offline = Bukkit.getOfflinePlayer(username);
        return offline.hasPlayedBefore() || offline.isOnline() ? offline.getUniqueId() : null;
    }

    private @NotNull VoteSnapshot toSnapshot(@NotNull VotePlayerEntity entity) {
        return new VoteSnapshot(
                entity.getPlayerUuid(),
                entity.getPlayerName(),
                entity.getTotalVotes(),
                entity.getMonthlyVotes(),
                entity.getCurrentStreak(),
                entity.getHighestStreak(),
                entity.getVotePoints(),
                entity.getLastVoteAt()
        );
    }
}
