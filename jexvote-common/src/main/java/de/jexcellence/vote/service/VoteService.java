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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VoteService {

    private static final Duration STREAK_TIMEOUT = Duration.ofHours(36);

    private final JavaPlugin plugin;
    private final Logger logger;
    private final PlatformScheduler scheduler;
    private final VotePlayerRepository playerRepository;
    private final VoteRecordRepository recordRepository;
    private final PendingVoteRewardRepository pendingRewardRepository;
    private final VoteRewardService rewardService;
    private final Map<String, VoteSite> voteSites;

    public VoteService(@NotNull JavaPlugin plugin,
                       @NotNull VotePlayerRepository playerRepository,
                       @NotNull VoteRecordRepository recordRepository,
                       @NotNull PendingVoteRewardRepository pendingRewardRepository,
                       @NotNull VoteRewardService rewardService,
                       @NotNull Map<String, VoteSite> voteSites) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.scheduler = PlatformScheduler.of(plugin);
        this.playerRepository = playerRepository;
        this.recordRepository = recordRepository;
        this.pendingRewardRepository = pendingRewardRepository;
        this.rewardService = rewardService;
        this.voteSites = voteSites;
    }

    public @NotNull CompletableFuture<Boolean> processVote(@NotNull Vote vote) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Processing vote: " + vote.username() + " / " + vote.serviceName());

                UUID uuid = resolveUuid(vote.username());
                if (uuid == null) {
                    logger.warning("Could not resolve UUID for voter: " + vote.username()
                            + " — player has never joined this server");
                    return false;
                }

                logger.fine("Resolved UUID for " + vote.username() + ": " + uuid);

                // Fire event on main thread and wait for result
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

                Boolean allowed = eventResult.join();
                if (!allowed) {
                    logger.info("Vote for " + vote.username() + " was cancelled by event listener");
                    return false;
                }

                VotePlayerEntity player = playerRepository.findByUuid(uuid)
                        .orElseGet(() -> {
                            logger.info("Creating new vote profile for " + vote.username() + " (" + uuid + ")");
                            VotePlayerEntity newPlayer = new VotePlayerEntity(uuid, vote.username());
                            playerRepository.create(newPlayer);
                            return newPlayer;
                        });

                player.setPlayerName(vote.username());
                resetMonthlyIfNeeded(player);
                updateStreak(player);

                VoteSite site = findSiteByServiceName(vote.serviceName());
                int points = site != null ? site.pointsPerVote() : 1;

                if (site == null) {
                    logger.warning("No vote site configured for service '" + vote.serviceName()
                            + "' — using default 1 point. Check sites.yml service-name mappings.");
                }

                player.setTotalVotes(player.getTotalVotes() + 1);
                player.setMonthlyVotes(player.getMonthlyVotes() + 1);
                player.setVotePoints(player.getVotePoints() + points);
                player.setLastVoteAt(vote.timestamp());

                playerRepository.update(player);

                VoteRecordEntity record = new VoteRecordEntity(
                        uuid, vote.username(), vote.serviceName(),
                        vote.address(), vote.timestamp());
                recordRepository.create(record);

                Player onlinePlayer = Bukkit.getPlayer(uuid);
                VoteSnapshot snapshot = toSnapshot(player);

                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                    scheduler.runAtEntity(onlinePlayer, () -> {
                        rewardService.grantRewards(onlinePlayer, vote.serviceName(), player.getCurrentStreak());
                        Bukkit.getPluginManager().callEvent(
                                new VoteRewardClaimedEvent(uuid, vote.serviceName(), snapshot));
                    });
                    logger.info("Vote processed for " + vote.username()
                            + " (online) — streak: " + player.getCurrentStreak()
                            + ", total: " + player.getTotalVotes());
                } else {
                    String rewardData = rewardService.serializeRewards(vote.serviceName(), player.getCurrentStreak());
                    if (rewardData != null) {
                        pendingRewardRepository.create(
                                new PendingVoteRewardEntity(uuid, vote.serviceName(), rewardData));
                    }
                    logger.info("Vote processed for " + vote.username()
                            + " (offline) — rewards queued, streak: " + player.getCurrentStreak()
                            + ", total: " + player.getTotalVotes());
                }

                return true;
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to process vote for " + vote.username(), e);
                return false;
            }
        });
    }

    public void deliverPendingRewards(@NotNull Player player) {
        pendingRewardRepository.findByPlayer(player.getUniqueId()).thenAccept(pending -> {
            if (pending.isEmpty()) return;

            scheduler.runAtEntity(player, () -> {
                for (PendingVoteRewardEntity reward : pending) {
                    rewardService.grantSerializedRewards(player, reward.getRewardData());
                }
            });

            for (PendingVoteRewardEntity reward : pending) {
                pendingRewardRepository.delete(reward.getId());
            }
            logger.info("Delivered " + pending.size() + " pending vote reward(s) to " + player.getName());
        });
    }

    public @NotNull CompletableFuture<VoteSnapshot> getPlayerStats(@NotNull UUID uuid) {
        return playerRepository.findByUuidAsync(uuid).thenApply(opt ->
                opt.map(this::toSnapshot).orElse(
                        new VoteSnapshot(uuid, null, 0, 0, 0, 0, 0, null))
        );
    }

    public @NotNull Map<String, VoteSite> getVoteSites() {
        return voteSites;
    }

    public @Nullable VoteSite findSiteByServiceName(@NotNull String serviceName) {
        String lower = serviceName.toLowerCase();
        for (VoteSite site : voteSites.values()) {
            if (site.serviceName().toLowerCase().equals(lower) ||
                    site.id().toLowerCase().equals(lower)) {
                return site;
            }
        }
        return null;
    }

    public void resetAllMonthlyVotes() {
        playerRepository.findAllAsync().thenAccept(players -> {
            for (VotePlayerEntity player : players) {
                player.setMonthlyVotes(0);
                player.setMonthlyResetMonth(YearMonth.now(ZoneId.systemDefault()).toString());
                playerRepository.update(player);
            }
        });
        logger.info("Reset monthly vote counts for all players");
    }

    private void updateStreak(@NotNull VotePlayerEntity player) {
        Instant lastVote = player.getLastVoteAt();
        if (lastVote == null || Duration.between(lastVote, Instant.now()).compareTo(STREAK_TIMEOUT) > 0) {
            player.setCurrentStreak(1);
        } else {
            player.setCurrentStreak(player.getCurrentStreak() + 1);
        }

        if (player.getCurrentStreak() > player.getHighestStreak()) {
            player.setHighestStreak(player.getCurrentStreak());
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
