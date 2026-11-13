package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.api.event.StreakMilestoneEvent;
import de.jexcellence.vote.api.event.VotePreRewardEvent;
import de.jexcellence.vote.api.event.VoteReceivedEvent;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.api.reward.VoteContext;
import de.jexcellence.vote.api.reward.VoteRewardDescriptor;
import de.jexcellence.vote.config.VoteConfig;
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

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    private final PendingRowSettler rowSettler;
    private final Map<UUID, CompletableFuture<Void>> pendingDeliveries = new ConcurrentHashMap<>();
    private final VoteRewardService rewardService;
    private final VoteBroadcastService broadcastService;
    private final MultiplierService multiplierService;
    private final @Nullable VotePartyService votePartyService;
    /** The read/query surface (snapshots, ranks, cooldowns, site lookups). */
    private final VoteStatsService stats;

    /** Reward SPI registry, set by {@link #setRewardSpi}; {@code null} until then. */
    private @Nullable VoteRewardProviderRegistry rewardSpi;
    /** Bonus descriptor executor, set by {@link #setRewardSpi}; {@code null} until then. */
    private @Nullable VoteDescriptorExecutor descriptorExecutor;

    private final AtomicReference<Map<String, VoteSite>> voteSites;
    private volatile Duration streakTimeout;
    private final VoteCommandSet voteCommands;
    private final OnlineVotePayout onlinePayout;
    private volatile int recordRetentionDays;
    private final AtomicReference<VoteConfig.FreezeSettings> freezeSettings;
    private final VoteConfig.BedrockSettings bedrockSettings;
    private final VoteConfig.DailyFlySettings dailyFly;

    /** Duplicate-vote guard: same voter+service delivered again within this window is ignored. */
    private static final long DEDUP_WINDOW_MS = 5_000L;
    /** Vote days, streak days and the monthly reset follow the server community time zone. */
    private static final ZoneId VOTE_DAY_ZONE = ZoneId.of("Europe/Berlin");
    /** Pending-entry service-name prefix that marks a serialized SPI/pre-reward descriptor blob. */
    private static final String SPI_PENDING_MARKER = "spi::";
    private static final String SPI_OFFLINE_REASON = "vote reward SPI (offline)";
    private static final VoteRewardService.StoredGrant EMPTY_GRANT = new VoteRewardService.StoredGrant(List.of(), null);
    private final Map<String, Long> recentVotes = new ConcurrentHashMap<>();
    /** Votes whose voter had no resolvable UUID yet - replayed on their first join. */
    private final UnresolvedVoteStore unresolvedVotes;

    /** Tracks server-down periods so vote streaks aren't broken by downtime the player couldn't avoid. */
    private final DowntimeTracker downtime;

    /**
     * Creates the vote service. The stats service shares the live {@code voteSites} reference, so a reload updates
     * its reads too; the downtime heartbeat runs every minute so a crash leaves a fresh last-alive time for the next
     * start's gap computation.
     */
    @SuppressWarnings("java:S107")
    public VoteService(@NotNull JavaPlugin plugin,
                       @NotNull VotePlayerRepository playerRepository,
                       @NotNull VoteRecordRepository recordRepository,
                       @NotNull PendingVoteRewardRepository pendingRewardRepository,
                       @NotNull VoteRewardService rewardService,
                       @NotNull VoteBroadcastService broadcastService,
                       @NotNull MultiplierService multiplierService,
                       @Nullable VotePartyService votePartyService,
                       @NotNull Map<String, VoteSite> voteSites,
                       int streakTimeoutHours,
                       @NotNull Map<Integer, List<String>> streakCommands,
                       int recordRetentionDays,
                       @NotNull VoteConfig.FreezeSettings freezeSettings,
                       @NotNull VoteConfig.BedrockSettings bedrockSettings,
                       @NotNull VoteConfig.DailyFlySettings dailyFly,
                       @NotNull List<String> dailyRewardCommands) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.scheduler = PlatformScheduler.of(plugin);
        this.playerRepository = playerRepository;
        this.recordRepository = recordRepository;
        this.pendingRewardRepository = pendingRewardRepository;
        this.rowSettler = new PendingRowSettler(pendingRewardRepository, logger, plugin.getDataFolder());
        this.rewardService = rewardService;
        this.broadcastService = broadcastService;
        this.multiplierService = multiplierService;
        this.votePartyService = votePartyService;
        this.voteSites = new AtomicReference<>(voteSites);
        this.stats = new VoteStatsService(playerRepository, recordRepository, this.voteSites);
        this.streakTimeout = Duration.ofHours(streakTimeoutHours);
        this.voteCommands = new VoteCommandSet(dailyRewardCommands, streakCommands);
        this.recordRetentionDays = recordRetentionDays;
        this.freezeSettings = new AtomicReference<>(freezeSettings);
        this.bedrockSettings = bedrockSettings;
        this.dailyFly = dailyFly;
        this.onlinePayout = new OnlineVotePayout(logger, rewardService, broadcastService, voteCommands,
                new OnlineVotePayout.Hooks(scheduler::runAtEntity, this::storePending,
                        (voter, service, bonus) -> queueBonusOffline(voter, service, bonus, null),
                        () -> this.descriptorExecutor, event -> Bukkit.getPluginManager().callEvent(event)));
        this.downtime = new DowntimeTracker(plugin);
        this.unresolvedVotes = new UnresolvedVoteStore(plugin.getDataFolder(), this.logger);
        this.downtime.initialize(logger);
        this.scheduler.runRepeating(this.downtime::heartbeat, 20L * 60L, 20L * 60L);
    }

    /**
     * Called by {@code /jexvote reload} to refresh mutable config state.
     */
    @SuppressWarnings("java:S107")
    public void reload(@NotNull Map<String, VoteSite> voteSites,
                       int streakTimeoutHours,
                       @NotNull Map<Integer, List<String>> streakCommands,
                       int recordRetentionDays,
                       boolean manualStreakClaim,
                       @NotNull MultiplierService.Settings multiplierSettings,
                       @NotNull VoteConfig.FreezeSettings freezeSettings,
                       @NotNull List<AbstractReward> defaultRewards,
                       @NotNull List<AbstractReward> guaranteedRewards,
                       @NotNull Map<Integer, List<AbstractReward>> streakRewards,
                       @NotNull Map<String, List<AbstractReward>> siteRewards,
                       @NotNull List<String> commandsOnVote) {
        this.voteSites.set(voteSites);
        this.streakTimeout = Duration.ofHours(streakTimeoutHours);
        this.voteCommands.setStreakCommands(streakCommands);
        this.recordRetentionDays = recordRetentionDays;
        this.rewardService.setManualStreakClaim(manualStreakClaim);
        this.rewardService.reloadRewards(defaultRewards, guaranteedRewards, streakRewards, siteRewards, commandsOnVote);
        this.multiplierService.reload(multiplierSettings);
        this.freezeSettings.set(freezeSettings);
    }

    /** Live vote-party progress as {@code {current, target}}; {@code {0,0}} when no party service. */
    public int[] votePartyProgress() {
        return votePartyService == null
                ? new int[]{0, 0}
                : new int[]{votePartyService.currentVotes(), votePartyService.targetVotes()};
    }

    /** Outcome of the pre-resolution step: either stop early (dup/queued) or proceed with a UUID. */
    private record PreResult(boolean done, boolean result, @Nullable UUID uuid) {
        static PreResult stop(boolean result) {
            return new PreResult(true, result, null);
        }
        static PreResult proceed(@NotNull UUID uuid) {
            return new PreResult(false, false, uuid);
        }
    }

    /**
     * Processes a vote without blocking: pre-resolves off the main thread, fires the sync event through a callback,
     * then continues the database work off the main thread. No thread waits on the main thread, so a stalled main
     * thread never pins a Votifier worker per vote.
     *
     * @return whether the vote was accepted (a queued vote of an unknown voter counts as accepted)
     */
    public @NotNull CompletableFuture<Boolean> processVote(@NotNull Vote vote) {
        return CompletableFuture.supplyAsync(() -> preResolve(vote))
                .thenCompose(pre -> {
                    if (pre.done()) {
                        return CompletableFuture.completedFuture(pre.result());
                    }
                    UUID uuid = pre.uuid();
                    return fireVoteReceivedEvent(vote, uuid).thenApplyAsync(allowed -> {
                        if (!Boolean.TRUE.equals(allowed)) {
                            logger.info(String.format("Vote for %s was cancelled by event listener", vote.username()));
                            return false;
                        }
                        completeVote(vote, uuid);
                        return true;
                    });
                })
                .exceptionally(e -> {
                    logger.log(Level.SEVERE, e, () -> String.format("Failed to process vote for %s", vote.username()));
                    return false;
                });
    }

    /**
     * Dedupe + UUID resolution (runs off the main thread). Returns a "stop" result for a
     * duplicate (false) or an unresolvable-and-queued voter (true), else "proceed" with the UUID.
     * Some setups deliver a vote twice (a second vote plugin, a relay, a site that re-sends); the atomic
     * {@code put} lets exactly one of two racing duplicates through. A voter who never joined is queued and replayed
     * on their first join, so neither the reward nor the streak is lost.
     */
    private @NotNull PreResult preResolve(@NotNull Vote vote) {
        logger.info(String.format("Processing vote: %s / %s", vote.username(), vote.serviceName()));

        String dedupeKey = vote.username().toLowerCase(Locale.ROOT) + '|'
                + vote.serviceName().toLowerCase(Locale.ROOT);
        long nowMs = System.currentTimeMillis();
        Long lastSeen = recentVotes.put(dedupeKey, nowMs);
        if (lastSeen != null && nowMs - lastSeen < DEDUP_WINDOW_MS) {
            logger.warning(String.format("Duplicate vote ignored for %s / %s (re-delivered within %dms)",
                    vote.username(), vote.serviceName(), DEDUP_WINDOW_MS));
            return PreResult.stop(false);
        }

        UUID uuid = resolveUuid(vote.username());
        if (uuid == null) {
            unresolvedVotes.add(vote);
            logger.info(String.format(
                    "Voter %s not resolvable yet - queued (%d pending) until first join",
                    vote.username(), unresolvedVotes.size()));
            return PreResult.stop(true);
        }
        logger.fine(String.format("Resolved UUID for %s: %s", vote.username(), uuid));
        return PreResult.proceed(uuid);
    }

    /** The reward pipeline for a resolved, non-cancelled vote (runs off the main thread). */
    private void completeVote(@NotNull Vote vote, @NotNull UUID uuid) {
        VotePlayerEntity player = findOrCreatePlayer(vote, uuid);
        int points = resolvePointsForSite(vote.serviceName());
        boolean firstDaily = applyVoteToPlayer(player, vote, points);

        recordRepository.create(new VoteRecordEntity(
                uuid, vote.username(), vote.serviceName(),
                vote.address(), vote.timestamp()));

        if (votePartyService != null) {
            votePartyService.recordVote(uuid);
        }

        deliverOrQueueRewards(vote, uuid, player, firstDaily);
        announceVote(vote, uuid);
    }

    /**
     * Fires the public "{player} voted" broadcast. Done here inside the shared
     * {@code processVote} flow - not in a single ingestion callback - so every
     * vote path announces the voter: the embedded Votifier server, the API
     * provider (external NuVotifier / website), and the admin test command.
     * Previously only the embedded server broadcast, so votes delivered through
     * the provider granted rewards (the crate) but nobody saw who voted.
     */
    private void announceVote(@NotNull Vote vote, @NotNull UUID uuid) {
        boolean voterShares = RewardSharePreference.isShared(uuid);
        scheduler.runSync(() -> broadcastService.broadcastVote(
                vote.username(), vote.serviceName(), uuid, voterShares));
    }

    /**
     * Fires the cancellable {@link VoteReceivedEvent} on the main thread and completes the
     * returned future with the not-cancelled verdict via a callback - never blocks the caller
     * (the old {@code join()} pinned a worker thread on the main thread).
     */
    private @NotNull CompletableFuture<Boolean> fireVoteReceivedEvent(@NotNull Vote vote, @NotNull UUID uuid) {
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
        return eventResult;
    }

    private @NotNull VotePlayerEntity findOrCreatePlayer(@NotNull Vote vote, @NotNull UUID uuid) {
        VotePlayerEntity player = playerRepository.findByUuid(uuid)
                .orElseGet(() -> {
                    logger.log(Level.INFO, () -> String.format(
                            "Creating new vote profile for %s (%s)", vote.username(), uuid));
                    VotePlayerEntity newPlayer = new VotePlayerEntity(uuid, vote.username());
                    initializeFreezes(newPlayer);
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
                    "No vote site configured for service '%s' - using default 1 point. "
                            + "Check sites.yml service-name mappings.", serviceName));
            return 1;
        }
        return site.pointsPerVote();
    }

    /**
     * Adds the vote to the player. The stored vote time is the processing time, not the Votifier timestamp: relays
     * and API votes can carry stale timestamps, and {@link #updateStreak} compares the stored time with now, so both
     * must come from the same clock.
     */
    private boolean applyVoteToPlayer(@NotNull VotePlayerEntity player, @NotNull Vote vote, int points) {
        int scaledPoints = (int) Math.round(points * multiplierService.current());
        player.setTotalVotes(player.getTotalVotes() + 1);
        player.setMonthlyVotes(player.getMonthlyVotes() + 1);
        player.setVotePoints(player.getVotePoints() + scaledPoints);
        player.setLastVoteAt(Instant.now());
        boolean firstDaily = applyDailyFlyDate(player);
        playerRepository.update(player);
        return firstDaily;
    }

    /**
     * Marks (once per Europe/Berlin day) and returns whether this is the player's first vote of the day and at
     * least one daily bonus is configured: the fly coupon or the daily-reward commands. The date is only claimed
     * when there is something to grant.
     */
    private boolean applyDailyFlyDate(@NotNull VotePlayerEntity player) {
        if (!dailyFly.enabled() && voteCommands.daily().isEmpty()) {
            return false;
        }
        String today = LocalDate.now(VOTE_DAY_ZONE).toString();
        if (today.equals(player.getDailyFlyDate())) {
            return false;
        }
        player.setDailyFlyDate(today);
        return true;
    }

    /**
     * Injects the reward SPI collaborators (V2). Called once at startup after the
     * executor is built (it needs {@link #grantVotePoints} as its points sink), so
     * they are set post-construction rather than bloating the constructor.
     */
    public void setRewardSpi(@NotNull VoteRewardProviderRegistry registry,
                             @NotNull VoteDescriptorExecutor executor) {
        this.rewardSpi = registry;
        this.descriptorExecutor = executor;
    }

    /**
     * Grants vote-points to a (possibly offline) player and persists the change.
     * The single write path for points-from-automation - the reward SPI's
     * {@code Points} descriptor and the future API write-hook both route here, so
     * every points grant is logged with its reason.
     *
     * @param uuid   the player
     * @param amount the points to add (a value {@code <= 0} is a no-op success)
     * @param reason a short audit reason (e.g. {@code "vote reward SPI"})
     * @return a future resolving to {@code true} when the points were applied
     */
    public @NotNull CompletableFuture<Boolean> grantVotePoints(@NotNull UUID uuid, int amount,
                                                               @NotNull String reason) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(true);
        }
        return playerRepository.findByUuidAsync(uuid).thenApply(opt -> {
            if (opt.isEmpty()) {
                logger.log(Level.WARNING, () -> String.format(
                        "Cannot grant %d vote-points to %s (%s): no player record", amount, uuid, reason));
                return false;
            }
            VotePlayerEntity player = opt.get();
            player.setVotePoints(player.getVotePoints() + amount);
            playerRepository.update(player);
            logger.log(Level.INFO, () -> String.format(
                    "Granted %d vote-points to %s (%s)", amount, uuid, reason));
            return true;
        });
    }

    /**
     * Collects the bonus reward descriptors for a vote: the registered SPI providers plus
     * the additions from the (async) {@link VotePreRewardEvent}. Must be called off the main
     * thread (the event is async) - i.e. from the vote-processing pipeline, before any
     * main/region hop. Returns an empty list when the SPI is off and no listener adds anything.
     */
    private @NotNull List<VoteRewardDescriptor> collectBonusRewards(@NotNull Vote vote, @NotNull UUID uuid,
                                                                    @NotNull VoteSnapshot snapshot, boolean online) {
        List<VoteRewardDescriptor> bonus = new ArrayList<>();
        VoteRewardProviderRegistry registry = this.rewardSpi;
        if (registry != null && registry.isActive()) {
            bonus.addAll(registry.collect(new VoteContext(
                    uuid, vote.username(), vote.serviceName(), snapshot, online, false)));
        }
        VotePreRewardEvent event = new VotePreRewardEvent(
                uuid, vote.username(), vote.serviceName(), snapshot, online);
        Bukkit.getPluginManager().callEvent(event);
        bonus.addAll(event.getAdditionalRewards());
        return bonus;
    }

    /**
     * Offline path for bonus descriptors: vote-points apply immediately (DB-backed, offline-safe),
     * everything else is serialized into a marked pending entry and replayed by the descriptor
     * executor when the player next joins.
     */
    private void queueBonusOffline(@NotNull UUID uuid, @NotNull String service,
                                   @NotNull List<VoteRewardDescriptor> bonus,
                                   @Nullable NormalProfileRewards normalProfile) {
        VoteDescriptorExecutor executor = this.descriptorExecutor;
        if (bonus.isEmpty()) {
            return;
        }
        if (executor == null) {
            OnlineVotePayout.logBonusDropped(logger, uuid, bonus.size());
            return;
        }
        List<VoteRewardDescriptor> queueable = new ArrayList<>();
        for (VoteRewardDescriptor descriptor : bonus) {
            if (descriptor instanceof VoteRewardDescriptor.Points p) {
                grantVotePoints(uuid, p.amount(), SPI_OFFLINE_REASON).exceptionally(ex -> {
                    logger.log(Level.WARNING, ex, () -> "Failed to grant offline vote-points to " + uuid);
                    return false;
                });
            } else {
                queueable.add(descriptor);
            }
        }
        if (queueable.isEmpty()) {
            return;
        }
        String blob = executor.serialize(queueable);
        if (blob == null) {
            return;
        }
        if (normalProfile != null) {
            normalProfile.hold(uuid, SPI_PENDING_MARKER + service, blob);
        } else {
            storePending(new PendingVoteRewardEntity(uuid, SPI_PENDING_MARKER + service, blob));
        }
    }

    /**
     * Grants one Streak Freeze to a (possibly offline) player - the programmatic
     * "streak grace" write-hook. Unlike the shop purchase this charges nothing and
     * ignores the owned cap (an admin/automation override). Consumed automatically on
     * the next vote that would otherwise break the streak.
     *
     * @return a future resolving to {@code true} when a freeze was granted
     */
    public @NotNull CompletableFuture<Boolean> forceStreakGrace(@NotNull UUID uuid) {
        return playerRepository.findByUuidAsync(uuid).thenApply(opt -> {
            if (opt.isEmpty()) {
                logger.log(Level.WARNING, () -> String.format(
                        "Cannot force streak grace for %s: no player record", uuid));
                return false;
            }
            VotePlayerEntity player = opt.orElseThrow();
            player.setStreakFreezes(player.getStreakFreezes() + 1);
            playerRepository.update(player);
            logger.log(Level.INFO, () -> String.format("Forced streak grace (+1 freeze) for %s", uuid));
            return true;
        });
    }

    /**
     * Pays a processed vote: right away for an online voter, kept for the Normal profile while the Season profile
     * is active, otherwise queued for the next join. A streak-reward day fires {@link StreakMilestoneEvent} on the
     * main thread. The bonus descriptors are collected here, on the async processing thread, because the
     * pre-reward event is async and must not fire from the player's thread.
     */
    private void deliverOrQueueRewards(@NotNull Vote vote, @NotNull UUID uuid,
                                       @NotNull VotePlayerEntity player, boolean firstDailyBonus) {
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        VoteSnapshot snapshot = stats.toSnapshot(player);
        int streak = player.getCurrentStreak();
        int consumedFreezes = player.getConsumedFreezesThisVote();
        int remainingFreezes = player.getStreakFreezes();
        int freshFreezeGrant = player.getFreshFreezeGrant();

        boolean online = onlinePlayer != null && onlinePlayer.isOnline();

        if (rewardService.getStreakRewards().containsKey(streak)) {
            scheduler.runSync(() -> Bukkit.getPluginManager().callEvent(
                    new StreakMilestoneEvent(uuid, streak, streak)));
        }

        List<VoteRewardDescriptor> bonus = collectBonusRewards(vote, uuid, snapshot, online);

        NormalProfileRewards normalProfile = NormalProfileRewards.current();
        if (normalProfile != null && normalProfile.routes(uuid)) {
            holdForNormalProfile(normalProfile, vote, uuid, streak, firstDailyBonus, bonus);
            logger.log(Level.INFO, () -> String.format("Vote processed for %s (Season profile) - rewards kept for "
                    + "the Normal profile, streak: %d, total: %d", vote.username(), streak, player.getTotalVotes()));
        } else if (online) {
            onlinePayout.pay(new PaidVote(onlinePlayer, vote, uuid, snapshot, streak, firstDailyBonus,
                    new PaidVote.FreezeNotice(consumedFreezes, remainingFreezes, freshFreezeGrant), bonus));
            logger.log(Level.INFO, () -> String.format("Vote processed for %s (online) - streak: %d, total: %d",
                    vote.username(), streak, player.getTotalVotes()));
        } else {
            onlinePayout.queueOffline(new PaidVote(null, vote, uuid, snapshot, streak, firstDailyBonus,
                    PaidVote.FreezeNotice.NONE, bonus));
            logger.log(Level.INFO, () -> String.format(
                    "Vote processed for %s (offline) - rewards queued, streak: %d, total: %d",
                    vote.username(), streak, player.getTotalVotes()));
        }
    }

    /**
     * A vote cast while the JExOneblock Season profile is active: the rewards, the daily bonus and the streak
     * commands wait in the pending queue for the Normal profile. An online voter still gets the thank-you message,
     * the effects and a note where the rewards went.
     */
    private void holdForNormalProfile(@NotNull NormalProfileRewards normalProfile, @NotNull Vote vote,
                                      @NotNull UUID uuid, int streak, boolean firstDailyBonus,
                                      @NotNull List<VoteRewardDescriptor> bonus) {
        String service = vote.serviceName();
        normalProfile.hold(uuid, service, rewardService.serializeRewards(
                service, streak, voteCommands.queuedFor(service, streak, firstDailyBonus)));
        queueBonusOffline(uuid, service, bonus, normalProfile);
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer == null || !onlinePlayer.isOnline()) {
            return;
        }
        boolean milestone = rewardService.getStreakRewards().containsKey(streak);
        scheduler.runAtEntity(onlinePlayer, () -> {
            broadcastService.notifyPlayer(onlinePlayer, service, streak);
            broadcastService.playVoteEffects(onlinePlayer, streak, milestone);
            normalProfile.tellWhere(onlinePlayer);
        });
    }

    /** Stores a pending row off the calling thread, which may be a player's or the main thread. */
    private void storePending(@NotNull PendingVoteRewardEntity row) {
        pendingRewardRepository.createAsync(row).exceptionally(ex -> {
            logger.log(Level.SEVERE, ex, () -> "Failed to store a pending vote reward of " + row.getPlayerUuid());
            return null;
        });
    }

    /**
     * Replays any votes that were queued while this player's name was unresolvable
     * (see {@link UnresolvedVoteStore}). Matching is done against ONLINE players only
     * ({@link #resolveOnline}) so the join thread never triggers a blocking name lookup;
     * the joining player is online now, so their queued votes match and are re-processed
     * - granting the reward and advancing the streak - while votes for still-absent
     * players stay queued.
     */
    private void replayUnresolvedVotes(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        List<Vote> replay = unresolvedVotes.drain(vote -> uuid.equals(resolveOnline(vote.username())));
        for (Vote vote : replay) {
            logger.info(String.format("Replaying queued vote for %s (%s)", player.getName(), vote.serviceName()));
            processVote(vote);
        }
    }

    /** Resolves a vote username to an ONLINE player's UUID (Java + Bedrock name variants), or null. */
    private @Nullable UUID resolveOnline(@NotNull String username) {
        for (String candidate : nameCandidates(username)) {
            Player online = Bukkit.getPlayerExact(candidate);
            if (online != null) {
                return online.getUniqueId();
            }
        }
        return null;
    }

    /**
     * Delivers the pending rewards the active profile may receive. Without JExOneblock profiles that is every
     * pending row; otherwise {@link NormalProfileRewards} keeps Season-profile rewards for the Normal profile.
     * Called on join and after every JExOneblock profile switch. Deliveries of one player run one after another, so
     * a profile switch right after a join never reads rows the join is still paying and settling.
     *
     * @param player the online player
     */
    public void deliverPendingRewards(@NotNull Player player) {
        replayUnresolvedVotes(player);
        UUID uuid = player.getUniqueId();
        CompletableFuture<Void> delivery = pendingDeliveries.compute(uuid, (key, previous) -> afterSettled(previous)
                .thenComposeAsync(ignored -> loadAndDeliver(player), scheduler::runAsync));
        delivery.whenComplete((ignored, error) -> pendingDeliveries.remove(uuid, delivery));
    }

    private static @NotNull CompletableFuture<Void> afterSettled(@Nullable CompletableFuture<Void> previous) {
        return previous == null ? CompletableFuture.completedFuture(null) : previous.handle((done, error) -> null);
    }

    private @NotNull CompletableFuture<Void> loadAndDeliver(@NotNull Player player) {
        return pendingRewardRepository.findByPlayer(player.getUniqueId())
                .thenCompose(rows -> rowSettler.payable(player.getUniqueId(), rows))
                .thenCompose(pending -> deliverPayable(player, pending))
                .exceptionally(ex -> {
                    final String playerName = player.getName();
                    logger.log(Level.WARNING, ex, () -> "Pending vote reward delivery failed for " + playerName);
                    return null;
                });
    }

    private @NotNull CompletableFuture<Void> deliverPayable(@NotNull Player player,
                                                            @NotNull List<PendingVoteRewardEntity> pending) {
        if (pending.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        NormalProfileRewards normalProfile = NormalProfileRewards.current();
        return normalProfile == null
                ? deliverBatch(player, pending, false)
                : normalProfile.handOver(player, pending, (batch, held) -> deliverBatch(player, batch, held));
    }

    /**
     * Grants one batch of pending rows on the player's thread, removes exactly those rows and reports what arrived.
     *
     * @param held whether the rows were kept from the Season profile for this Normal profile
     * @return completes once every grant of the batch finished
     */
    private @NotNull CompletableFuture<Void> deliverBatch(@NotNull Player player,
                                                          @NotNull List<PendingVoteRewardEntity> pending,
                                                          boolean held) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (pending.isEmpty()) {
            done.complete(null);
            return done;
        }
        scheduler.runAtEntity(player, () -> {
            if (!player.isOnline()) {
                done.complete(null);
                return;
            }
            List<CompletableFuture<VoteRewardService.StoredGrant>> grants = new ArrayList<>(pending.size());
            CompletableFuture<Void> previous = CompletableFuture.completedFuture(null);
            for (PendingVoteRewardEntity reward : pending) {
                CompletableFuture<VoteRewardService.StoredGrant> grant =
                        previous.thenCompose(ignored -> grantOnPlayerThread(player, reward));
                grants.add(grant);
                previous = grant.thenApply(ignored -> null);
            }
            CompletableFuture.allOf(grants.toArray(CompletableFuture[]::new)).whenComplete((ignored, err) -> {
                try {
                    finishBatch(player, pending, grants, held).whenComplete((settled, error) -> done.complete(null));
                } catch (Exception ex) {
                    logger.log(Level.SEVERE, ex, () -> "Finishing a pending vote reward batch failed");
                    done.complete(null);
                }
            });
        }, () -> done.complete(null));
        return done;
    }

    /** Settles a granted batch, tells the player what arrived and completes once every row is settled. */
    private @NotNull CompletableFuture<Void> finishBatch(
            @NotNull Player player, @NotNull List<PendingVoteRewardEntity> pending,
            @NotNull List<CompletableFuture<VoteRewardService.StoredGrant>> grants, boolean held) {
        CompletableFuture<Void> settled = settleRows(pending, grants);
        List<String> received = grants.stream()
                .map(grant -> grant.getNow(EMPTY_GRANT).received())
                .flatMap(List::stream)
                .toList();
        scheduler.runAtEntity(player, () ->
                broadcastService.notifyRewardsDelivered(player, pending.size(), received, held));
        final int deliveredCount = pending.size();
        final String deliveredTo = player.getName();
        logger.log(Level.INFO,
                () -> "Delivered " + deliveredCount + " pending vote reward(s) to " + deliveredTo);
        return settled;
    }

    /**
     * Grants one pending row on the player's thread after the previous row finished, so the coin deposits of
     * different rows never overlap. A player who left before it started keeps the whole row.
     */
    private @NotNull CompletableFuture<VoteRewardService.StoredGrant> grantOnPlayerThread(
            @NotNull Player player, @NotNull PendingVoteRewardEntity row) {
        CompletableFuture<VoteRewardService.StoredGrant> result = new CompletableFuture<>();
        VoteRewardService.StoredGrant keepWholeRow = new VoteRewardService.StoredGrant(List.of(), row.getRewardData());
        scheduler.runAtEntity(player, () -> {
            if (!player.isOnline()) {
                result.complete(keepWholeRow);
                return;
            }
            grantPending(player, row)
                    .whenComplete((grant, error) -> result.complete(grant == null ? keepWholeRow : grant));
        }, () -> result.complete(keepWholeRow));
        return result;
    }

    /**
     * Grants one pending row. SPI/pre-reward descriptor blobs are marked in the service name and replayed through
     * the descriptor executor; everything else is a config-reward blob. Never fails: a row that could not be granted
     * is kept whole for the next join, so nothing is lost to a broken or unreadable row.
     */
    private @NotNull CompletableFuture<VoteRewardService.StoredGrant> grantPending(
            @NotNull Player player, @NotNull PendingVoteRewardEntity reward) {
        VoteDescriptorExecutor executor = this.descriptorExecutor;
        try {
            boolean isBonusBlob = executor != null
                    && NormalProfileRewards.sourceOf(reward.getServiceName()).startsWith(SPI_PENDING_MARKER);
            CompletableFuture<VoteRewardService.StoredGrant> grant = isBonusBlob
                    ? executor.grantSerialized(player, reward.getRewardData())
                            .thenApply(received -> new VoteRewardService.StoredGrant(received, null))
                    : rewardService.grantSerializedRewards(player, reward.getRewardData());
            return grant.exceptionally(ex -> keepAfterFailure(player, reward, ex));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(keepAfterFailure(player, reward, e));
        }
    }

    private @NotNull VoteRewardService.StoredGrant keepAfterFailure(@NotNull Player player,
                                                                    @NotNull PendingVoteRewardEntity row,
                                                                    @NotNull Throwable failure) {
        final String playerName = player.getName();
        logger.log(Level.SEVERE, failure, () -> "Pending vote reward of " + playerName
                + " could not be granted - kept for the next join");
        return new VoteRewardService.StoredGrant(List.of(), row.getRewardData());
    }

    /**
     * Settles the delivered rows (see {@link PendingRowSettler}), so rows queued meanwhile or held for another profile
     * stay. A row nothing was taken from (unreadable, or the player left first) stays as it is.
     *
     * @return completes once every row is settled
     */
    private @NotNull CompletableFuture<Void> settleRows(
            @NotNull List<PendingVoteRewardEntity> rows,
            @NotNull List<CompletableFuture<VoteRewardService.StoredGrant>> grants) {
        List<CompletableFuture<Void>> settles = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            settles.add(rowSettler.settle(rows.get(index), grants.get(index).getNow(EMPTY_GRANT).remaining()));
        }
        return CompletableFuture.allOf(settles.toArray(CompletableFuture[]::new));
    }

    public @NotNull VoteStatsService stats() {
        return stats;
    }

    public @NotNull CompletableFuture<VoteSnapshot> getPlayerStats(@NotNull UUID uuid) {
        return stats.getPlayerStats(uuid);
    }

    public @NotNull Map<String, VoteSite> getVoteSites() {
        return stats.getVoteSites();
    }

    public @NotNull List<String> serviceNames() {
        return stats.serviceNames();
    }

    public @NotNull CompletableFuture<Long> secondsUntilNextVote(@NotNull UUID uuid,
                                                                 @NotNull String serviceName) {
        return stats.secondsUntilNextVote(uuid, serviceName);
    }

    public @NotNull CompletableFuture<Integer> getAllTimeRank(@NotNull UUID uuid) {
        return stats.getAllTimeRank(uuid);
    }

    public @NotNull CompletableFuture<Integer> getMonthlyRank(@NotNull UUID uuid) {
        return stats.getMonthlyRank(uuid);
    }

    public @NotNull CompletableFuture<Map<String, Long>> receivedServiceNames() {
        return stats.receivedServiceNames();
    }

    public @NotNull CompletableFuture<Map<String, Long>> voteCooldownsSeconds(@NotNull UUID uuid) {
        return stats.voteCooldownsSeconds(uuid);
    }

    public @NotNull VoteBroadcastService getBroadcastService() {
        return broadcastService;
    }

    public @Nullable VoteSite findSiteByServiceName(@NotNull String serviceName) {
        return stats.findSiteByServiceName(serviceName);
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

    public @NotNull CompletableFuture<Boolean> setStreak(@NotNull UUID uuid, int streak) {
        return playerRepository.findByUuidAsync(uuid).thenApply(opt -> {
            if (opt.isEmpty()) return false;

            VotePlayerEntity player = opt.orElseThrow();
            player.setCurrentStreak(streak);
            if (streak > player.getHighestStreak()) {
                player.setHighestStreak(streak);
            }
            playerRepository.update(player);
            return true;
        });
    }

    public void resetAllMonthlyVotes() {
        playerRepository.findAllAsync().thenAccept(players -> {
            String currentMonth = YearMonth.now(VOTE_DAY_ZONE).toString();
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

    /**
     * Advances the streak at most once per Europe/Berlin day: a second vote on the same day still pays, but leaves the
     * streak number alone. Server downtime is taken off the gap, because nobody can vote while the Votifier port is
     * unreachable, so a maintenance window never breaks a streak.
     */
    private void updateStreak(@NotNull VotePlayerEntity player) {
        player.setConsumedFreezesThisVote(0);
        Instant lastVote = player.getLastVoteAt();

        if (lastVote == null) {
            player.setCurrentStreak(1);
            recordHighestStreak(player);
            return;
        }

        Instant now = Instant.now();
        if (lastVote.atZone(VOTE_DAY_ZONE).toLocalDate().equals(now.atZone(VOTE_DAY_ZONE).toLocalDate())) {
            return;
        }

        Duration rawGap = Duration.between(lastVote, now);
        Duration serverDown = downtime.overlapping(lastVote, now);
        Duration gap = rawGap.minus(serverDown);
        if (gap.isNegative()) {
            gap = Duration.ZERO;
        }
        if (gap.compareTo(streakTimeout) <= 0) {
            player.setCurrentStreak(player.getCurrentStreak() + 1);
            recordHighestStreak(player);
            return;
        }

        if (tryConsumeFreezes(player, gap)) {
            player.setCurrentStreak(player.getCurrentStreak() + 1);
        } else {
            player.setCurrentStreak(1);
        }
        recordHighestStreak(player);
    }

    /**
     * Attempts to cover a streak gap that exceeds the timeout using owned
     * Streak Freezes. Each freeze absorbs one {@code duration-hours} window
     * beyond the normal timeout. Returns {@code true} (and decrements the
     * owned freezes) only when the player has enough to cover every missed
     * window; otherwise the streak is allowed to break. The overflow is measured in exact fractional hours, because
     * {@code Duration.toHours()} truncates and would under-count by a whole window right at a boundary.
     */
    private boolean tryConsumeFreezes(@NotNull VotePlayerEntity player, @NotNull Duration gap) {
        VoteConfig.FreezeSettings settings = freezeSettings.get();
        if (!settings.enabled() || player.getStreakFreezes() <= 0) {
            return false;
        }

        double overflowHours = gap.minus(streakTimeout).toNanos() / 3_600_000_000_000.0;
        long windowsNeeded = Math.max(1L,
                (long) Math.ceil(overflowHours / settings.durationHours()));
        if (player.getStreakFreezes() < windowsNeeded) {
            return false;
        }

        int consumed = (int) windowsNeeded;
        player.setStreakFreezes(player.getStreakFreezes() - consumed);
        player.setConsumedFreezesThisVote(consumed);
        return true;
    }

    private void recordHighestStreak(@NotNull VotePlayerEntity player) {
        if (player.getCurrentStreak() > player.getHighestStreak()) {
            player.setHighestStreak(player.getCurrentStreak());
        }
    }

    /**
     * Sets the free Streak Freeze grant on a freshly created profile.
     */
    private void initializeFreezes(@NotNull VotePlayerEntity player) {
        VoteConfig.FreezeSettings settings = freezeSettings.get();
        if (settings.enabled() && settings.freeAmount() > 0) {
            player.setStreakFreezes(settings.freeAmount());
            player.setFreshFreezeGrant(settings.freeAmount());
        }
        player.setFreezeInitialized(true);
    }

    /**
     * One-time, idempotent back-fill: grants the configured free Streak Freeze
     * amount to existing players whose profile predates the feature. Guarded by
     * {@code freezeInitialized} so it never double-grants across restarts.
     */
    public void initializeFreezesForExistingPlayers() {
        VoteConfig.FreezeSettings settings = freezeSettings.get();
        if (!settings.enabled() || settings.freeAmount() <= 0) {
            return;
        }
        playerRepository.findAllAsync().thenAccept(players -> {
            int granted = 0;
            for (VotePlayerEntity player : players) {
                if (!player.isFreezeInitialized()) {
                    player.setStreakFreezes(player.getStreakFreezes() + settings.freeAmount());
                    player.setFreezeInitialized(true);
                    playerRepository.update(player);
                    granted++;
                }
            }
            final int count = granted;
            if (count > 0) {
                logger.log(Level.INFO, () -> String.format(
                        "Granted free Streak Freeze to %d existing player(s)", count));
            }
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "Free Streak Freeze back-fill failed", ex);
            return null;
        });
    }

    private void resetMonthlyIfNeeded(@NotNull VotePlayerEntity player) {
        String currentMonth = YearMonth.now(VOTE_DAY_ZONE).toString();
        if (!currentMonth.equals(player.getMonthlyResetMonth())) {
            player.setMonthlyVotes(0);
            player.setMonthlyResetMonth(currentMonth);
        }
    }

    /**
     * Resolves a vote name: the raw name first (Java accounts), then the Bedrock (Floodgate) variants, because a
     * Bedrock voter types their gamertag while their in-game name carries the Floodgate prefix.
     */
    private @Nullable UUID resolveUuid(@NotNull String username) {
        for (String candidate : nameCandidates(username)) {
            UUID resolved = resolveExact(candidate);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    /** Online-exact then offline (played-before) resolution for one exact name. */
    private @Nullable UUID resolveExact(@NotNull String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() || offline.isOnline() ? offline.getUniqueId() : null;
    }

    /**
     * The names to try for a vote username, in order: the raw name, then the
     * Floodgate-prefixed forms (with and without space→underscore replacement).
     * Skips prefix variants when the name already carries the prefix, and
     * de-duplicates so a blank-prefix config just yields the raw name.
     */
    private @NotNull List<String> nameCandidates(@NotNull String username) {
        List<String> candidates = new ArrayList<>();
        candidates.add(username);
        String prefix = bedrockSettings.namePrefix();
        if (!prefix.isEmpty() && !username.startsWith(prefix)) {
            candidates.add(prefix + username);
            if (bedrockSettings.replaceSpaces() && username.indexOf(' ') >= 0) {
                candidates.add(prefix + username.replace(' ', '_'));
            }
        }
        return candidates.stream().distinct().toList();
    }

    /**
     * Persists a heartbeat timestamp to disk while the server is running so the
     * next boot can compute how long the server was actually down. Downtime
     * windows are appended to a small JSON-ish file and consulted during streak
     * gap evaluation so a player's streak doesn't break just because the server
     * was offline through the timeout window. Bounded to 90 days of history and
     * an in-memory cap of 100 entries so the file stays tiny.
     */
    private static final class DowntimeTracker {

        /** Minimum gap to consider a real downtime window (guards against harmless clock skew). */
        private static final long MIN_DOWNTIME_SECONDS = 180L;
        /** Only remember downtime younger than this - no streak can be older. */
        private static final long RETENTION_DAYS = 90L;
        private static final int MAX_ENTRIES = 100;

        private final JavaPlugin plugin;
        private final File heartbeatFile;
        private final File windowsFile;
        private final List<long[]> windows = new ArrayList<>();

        DowntimeTracker(@NotNull JavaPlugin plugin) {
            this.plugin = plugin;
            File dir = plugin.getDataFolder();
            if (!dir.exists()) dir.mkdirs();
            this.heartbeatFile = new File(dir, "uptime-heartbeat.txt");
            this.windowsFile = new File(dir, "downtime-windows.txt");
        }

        /**
         * On plugin enable: read the last heartbeat, compute the gap to now, and
         * if it exceeds the noise floor record it as a downtime window.
         */
        void initialize(@NotNull Logger logger) {
            loadWindows();
            long lastAlive = readHeartbeat();
            long now = Instant.now().getEpochSecond();
            if (lastAlive > 0L && now - lastAlive >= MIN_DOWNTIME_SECONDS) {
                windows.add(new long[]{lastAlive, now});
                trimAndSaveWindows();
                long down = now - lastAlive;
                logger.log(Level.INFO, () -> "[vote-uptime] recorded "
                        + Duration.ofSeconds(down).toMinutes()
                        + " min downtime window - streaks that overlap it will be forgiven");
            }
            writeHeartbeat(now);
        }

        void heartbeat() {
            writeHeartbeat(Instant.now().getEpochSecond());
        }

        /**
         * Total downtime that overlaps ({@code from}, {@code to}). Used by the
         * streak gap check so an offline period isn't counted against the player.
         */
        @NotNull Duration overlapping(@NotNull Instant from, @NotNull Instant to) {
            long fromSec = from.getEpochSecond();
            long toSec = to.getEpochSecond();
            if (toSec <= fromSec) return Duration.ZERO;
            long overlap = 0L;
            synchronized (windows) {
                for (long[] window : windows) {
                    long start = Math.max(fromSec, window[0]);
                    long end = Math.min(toSec, window[1]);
                    if (end > start) {
                        overlap += (end - start);
                    }
                }
            }
            return Duration.ofSeconds(overlap);
        }

        private long readHeartbeat() {
            if (!heartbeatFile.exists()) return 0L;
            try {
                String content = Files.readString(heartbeatFile.toPath()).trim();
                return content.isEmpty() ? 0L : Long.parseLong(content);
            } catch (Exception ex) {
                return 0L;
            }
        }

        private void writeHeartbeat(long epochSecond) {
            try {
                Files.writeString(heartbeatFile.toPath(), Long.toString(epochSecond));
            } catch (Exception ex) {
                plugin.getLogger().log(Level.FINE,
                        () -> "[vote-uptime] failed to write heartbeat: " + ex.getMessage());
            }
        }

        private void loadWindows() {
            if (!windowsFile.exists()) return;
            try {
                for (String line : Files.readAllLines(windowsFile.toPath())) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    String[] parts = trimmed.split(",");
                    if (parts.length != 2) continue;
                    try {
                        long start = Long.parseLong(parts[0].trim());
                        long end = Long.parseLong(parts[1].trim());
                        if (end > start) {
                            windows.add(new long[]{start, end});
                        }
                    } catch (NumberFormatException malformedLine) {
                        plugin.getLogger().log(Level.FINE, () -> "[vote-uptime] skipped a malformed line: " + trimmed);
                    }
                }
            } catch (Exception ex) {
                plugin.getLogger().log(Level.FINE,
                        () -> "[vote-uptime] failed to load downtime windows: " + ex.getMessage());
            }
        }

        private void trimAndSaveWindows() {
            long cutoff = Instant.now().getEpochSecond()
                    - Duration.ofDays(RETENTION_DAYS).getSeconds();
            synchronized (windows) {
                windows.removeIf(w -> w[1] < cutoff);
                while (windows.size() > MAX_ENTRIES) {
                    windows.remove(0);
                }
                try {
                    StringBuilder sb = new StringBuilder();
                    for (long[] w : windows) {
                        sb.append(w[0]).append(',').append(w[1]).append('\n');
                    }
                    Files.writeString(windowsFile.toPath(), sb.toString());
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.FINE,
                            () -> "[vote-uptime] failed to save downtime windows: " + ex.getMessage());
                }
            }
        }
    }
}
