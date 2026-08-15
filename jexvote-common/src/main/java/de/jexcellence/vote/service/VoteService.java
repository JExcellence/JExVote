package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.api.event.VoteReceivedEvent;
import de.jexcellence.vote.api.event.VoteRewardClaimedEvent;
import de.jexcellence.vote.api.model.VoteSnapshot;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final VoteRewardService rewardService;
    private final VoteBroadcastService broadcastService;
    private final MultiplierService multiplierService;
    private final @Nullable VotePartyService votePartyService;

    private final AtomicReference<Map<String, VoteSite>> voteSites;
    // volatile is sufficient: single-write / multi-read
    private volatile Duration streakTimeout;
    private final AtomicReference<Map<Integer, List<String>>> streakCommands;
    // volatile is sufficient: single-write / multi-read
    private volatile int recordRetentionDays;
    private final AtomicReference<VoteConfig.FreezeSettings> freezeSettings;
    private final VoteConfig.BedrockSettings bedrockSettings;
    private final VoteConfig.DailyFlySettings dailyFly;
    private final List<String> dailyRewardCommands;

    /** Duplicate-vote guard: same voter+service delivered again within this window is ignored. */
    private static final long DEDUP_WINDOW_MS = 5_000L;
    private final Map<String, Long> recentVotes = new ConcurrentHashMap<>();

    /** Tracks server-down periods so vote streaks aren't broken by downtime the player couldn't avoid. */
    private final DowntimeTracker downtime;

    // Configuration group — suppressed (S107)
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
        this.rewardService = rewardService;
        this.broadcastService = broadcastService;
        this.multiplierService = multiplierService;
        this.votePartyService = votePartyService;
        this.voteSites = new AtomicReference<>(voteSites);
        this.streakTimeout = Duration.ofHours(streakTimeoutHours);
        this.streakCommands = new AtomicReference<>(streakCommands);
        this.recordRetentionDays = recordRetentionDays;
        this.freezeSettings = new AtomicReference<>(freezeSettings);
        this.bedrockSettings = bedrockSettings;
        this.dailyFly = dailyFly;
        this.dailyRewardCommands = dailyRewardCommands;
        this.downtime = new DowntimeTracker(plugin);
        this.downtime.initialize(logger);
        // Heartbeat every minute so a crash / kill -9 leaves a fresh-enough
        // last-alive timestamp for the next boot's gap computation.
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
                       @NotNull VoteConfig.FreezeSettings freezeSettings) {
        this.voteSites.set(voteSites);
        this.streakTimeout = Duration.ofHours(streakTimeoutHours);
        this.streakCommands.set(streakCommands);
        this.recordRetentionDays = recordRetentionDays;
        this.rewardService.setManualStreakClaim(manualStreakClaim);
        this.multiplierService.reload(multiplierSettings);
        this.freezeSettings.set(freezeSettings);
    }

    /** Live vote-party progress as {@code {current, target}}; {@code {0,0}} when no party service. */
    public int[] votePartyProgress() {
        return votePartyService == null
                ? new int[]{0, 0}
                : new int[]{votePartyService.currentVotes(), votePartyService.targetVotes()};
    }

    public @NotNull CompletableFuture<Boolean> processVote(@NotNull Vote vote) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info(String.format("Processing vote: %s / %s", vote.username(), vote.serviceName()));

                // Duplicate-vote guard: some setups deliver the same vote twice (a second
                // vote plugin also listening, a relay, or a site that re-sends). Without
                // this every reward doubles — points, streak, and the first-of-day fly
                // bonus (the reported 2×15m fly). ConcurrentHashMap.put is atomic, so of
                // two racing duplicates exactly one proceeds.
                String dedupeKey = vote.username().toLowerCase(Locale.ROOT) + '|'
                        + vote.serviceName().toLowerCase(Locale.ROOT);
                long nowMs = System.currentTimeMillis();
                Long lastSeen = recentVotes.put(dedupeKey, nowMs);
                if (lastSeen != null && nowMs - lastSeen < DEDUP_WINDOW_MS) {
                    logger.warning(String.format("Duplicate vote ignored for %s / %s (re-delivered within %dms)",
                            vote.username(), vote.serviceName(), DEDUP_WINDOW_MS));
                    return false;
                }

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
                boolean firstDaily = applyVoteToPlayer(player, vote, points);

                recordRepository.create(new VoteRecordEntity(
                        uuid, vote.username(), vote.serviceName(),
                        vote.address(), vote.timestamp()));

                if (votePartyService != null) {
                    votePartyService.recordVote(uuid);
                }

                deliverOrQueueRewards(vote, uuid, player, firstDaily);
                announceVote(vote, uuid);
                return true;
            } catch (Exception e) {
                logger.log(Level.SEVERE, e, () -> String.format("Failed to process vote for %s", vote.username()));
                return false;
            }
        });
    }

    /**
     * Fires the public "{player} voted" broadcast. Done here inside the shared
     * {@code processVote} flow — not in a single ingestion callback — so every
     * vote path announces the voter: the embedded Votifier server, the API
     * provider (external NuVotifier / website), and the admin test command.
     * Previously only the embedded server broadcast, so votes delivered through
     * the provider granted rewards (the crate) but nobody saw who voted.
     */
    private void announceVote(@NotNull Vote vote, @NotNull UUID uuid) {
        scheduler.runSync(() -> broadcastService.broadcastVote(
                vote.username(), vote.serviceName(), uuid));
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
                    logger.log(Level.INFO, () -> String.format("Creating new vote profile for %s (%s)", vote.username(), uuid));
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
                    "No vote site configured for service '%s' — using default 1 point. Check sites.yml service-name mappings.",
                    serviceName));
            return 1;
        }
        return site.pointsPerVote();
    }

    private boolean applyVoteToPlayer(@NotNull VotePlayerEntity player, @NotNull Vote vote, int points) {
        int scaledPoints = (int) Math.round(points * multiplierService.current());
        player.setTotalVotes(player.getTotalVotes() + 1);
        player.setMonthlyVotes(player.getMonthlyVotes() + 1);
        player.setVotePoints(player.getVotePoints() + scaledPoints);
        // Store PROCESSING time, not the vote's Votifier timestamp. NuVotifier
        // relays and API-path votes can carry stale/wrong timestamps; storing
        // `Instant.now()` keeps updateStreak's "same calendar day" and gap
        // calculations honest — they compare stored lastVoteAt vs Instant.now(),
        // so both sides must use the same clock (see updateStreak).
        player.setLastVoteAt(Instant.now());
        boolean firstDaily = applyDailyFlyDate(player);
        playerRepository.update(player);
        return firstDaily;
    }

    private boolean applyDailyFlyDate(@NotNull VotePlayerEntity player) {
        if (!dailyFly.enabled() && dailyRewardCommands.isEmpty()) {
            return false;
        }
        String today = LocalDate.now(ZoneId.systemDefault()).toString();
        if (today.equals(player.getDailyFlyDate())) {
            return false;
        }
        player.setDailyFlyDate(today);
        return true;
    }

    private void deliverOrQueueRewards(@NotNull Vote vote, @NotNull UUID uuid,
                                       @NotNull VotePlayerEntity player, boolean firstDailyBonus) {
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        VoteSnapshot snapshot = toSnapshot(player);
        int streak = player.getCurrentStreak();
        int consumedFreezes = player.getConsumedFreezesThisVote();
        int remainingFreezes = player.getStreakFreezes();
        int freshFreezeGrant = player.getFreshFreezeGrant();

        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            scheduler.runAtEntity(onlinePlayer, () ->
                    deliverOnlineRewards(onlinePlayer, vote, uuid, snapshot, streak,
                            firstDailyBonus, consumedFreezes, remainingFreezes, freshFreezeGrant));
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

    private void deliverOnlineRewards(@NotNull Player onlinePlayer, @NotNull Vote vote,
                                         @NotNull UUID uuid, @NotNull VoteSnapshot snapshot,
                                         int streak, boolean firstDailyBonus,
                                         int consumedFreezes, int remainingFreezes,
                                         int freshFreezeGrant) {
        rewardService.grantRewards(onlinePlayer, vote.serviceName(), streak);
        executeStreakCommands(onlinePlayer, vote.serviceName(), streak);
        broadcastService.notifyPlayer(onlinePlayer, vote.serviceName(), streak);
        if (firstDailyBonus) {
            for (String command : dailyRewardCommands) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        command.replace("{player}", onlinePlayer.getName()));
            }
        }
        if (rewardService.hasGuaranteedRewards()) {
            broadcastService.notifyGuaranteedReward(onlinePlayer);
        }
        if (freshFreezeGrant > 0) {
            R18nManager.getInstance().msg("vote.freeze.granted").prefix()
                    .with("amount", String.valueOf(freshFreezeGrant))
                    .send(onlinePlayer);
        }
        if (consumedFreezes > 0) {
            R18nManager.getInstance().msg("vote.freeze.saved").prefix()
                    .with("consumed", String.valueOf(consumedFreezes))
                    .with("remaining", String.valueOf(remainingFreezes))
                    .with("streak", String.valueOf(streak))
                    .send(onlinePlayer);
        }
        Bukkit.getPluginManager().callEvent(
                new VoteRewardClaimedEvent(uuid, vote.serviceName(), snapshot));
    }

    public void deliverPendingRewards(@NotNull Player player) {
        pendingRewardRepository.findByPlayer(player.getUniqueId()).thenAccept(pending -> {
            if (pending.isEmpty()) return;

            scheduler.runAtEntity(player, () -> {
                for (PendingVoteRewardEntity reward : pending) {
                    try {
                        rewardService.grantSerializedRewards(player, reward.getRewardData());
                    } catch (Exception e) {
                        final String playerName = player.getName();
                        logger.log(Level.WARNING, e,
                                () -> "Failed to deliver pending reward to " + playerName);
                    }
                }

                // Delete only after successful delivery
                for (PendingVoteRewardEntity reward : pending) {
                    pendingRewardRepository.delete(reward.getId());
                }

                broadcastService.notifyPendingRewards(player, pending.size());
                final int deliveredCount = pending.size();
                final String deliveredTo = player.getName();
                logger.log(Level.INFO, () -> "Delivered " + deliveredCount
                        + " pending vote reward(s) to " + deliveredTo);
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

    /**
     * Every distinct service name that has actually been <b>received</b> in a vote (as
     * stored, un-normalised) → its most recent epoch-seconds. Used by the service
     * diagnostics command to spot names that match no configured site's {@code service-name}.
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
        Map<String, VoteSite> sites = getVoteSites();
        return recordRepository.findByPlayer(uuid).thenApply(records -> {
            // Key by lowercased service name so a casing/whitespace mismatch between the
            // recorded vote and the configured site (a common Votifier setup gotcha) still
            // maps the vote to its site instead of showing "always votable".
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
                long lastEpoch = latestEpoch.getOrDefault(site.serviceName().trim().toLowerCase(Locale.ROOT), 0L);
                remaining.put(site.serviceName(), site.secondsUntilNextVote(lastEpoch));
            }
            return remaining;
        });
    }

    public @NotNull VoteBroadcastService getBroadcastService() {
        return broadcastService;
    }

    public @Nullable VoteSite findSiteByServiceName(@NotNull String serviceName) {
        // Locale.ROOT avoids the classic Turkish-locale "i" bug: on a server
        // running under tr/az locales, "I".toLowerCase() yields 'ı' (dotless i),
        // not 'i', so a service-name comparison without ROOT can silently fail
        // to match a correctly configured site. Consistent with the ROOT-based
        // matching already used in voteCooldownsSeconds().
        String lower = serviceName.toLowerCase(Locale.ROOT);
        return voteSites.get().values().stream()
                .filter(site -> site.serviceName().toLowerCase(Locale.ROOT).equals(lower) ||
                        site.id().toLowerCase(Locale.ROOT).equals(lower))
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
        player.setConsumedFreezesThisVote(0);
        Instant lastVote = player.getLastVoteAt();

        if (lastVote == null) {
            player.setCurrentStreak(1);
            recordHighestStreak(player);
            return;
        }

        // The streak advances at most once per calendar day: if the previous
        // processed vote already fell on today, the day is counted, so leave
        // currentStreak untouched (keys/rewards still process — only the streak
        // number is capped). No per-config vote timezone exists, so the server's
        // local zone is used, consistent with the monthly-reset logic.
        Instant now = Instant.now();
        ZoneId zone = ZoneId.systemDefault();
        if (lastVote.atZone(zone).toLocalDate().equals(now.atZone(zone).toLocalDate())) {
            return;
        }

        Duration rawGap = Duration.between(lastVote, now);
        // Subtract periods the server was down — a player can't vote while the
        // Votifier port is unreachable. Without this the streak breaks whenever
        // a maintenance window straddles the timeout, which is unfair.
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
     * window; otherwise the streak is allowed to break.
     */
    private boolean tryConsumeFreezes(@NotNull VotePlayerEntity player, @NotNull Duration gap) {
        VoteConfig.FreezeSettings settings = freezeSettings.get();
        if (!settings.enabled() || player.getStreakFreezes() <= 0) {
            return false;
        }

        // Use the exact fractional-hour overflow, not Duration.toHours() (which
        // truncates the sub-hour remainder). Truncating here can under-count by
        // a whole window right at a duration-hours boundary — e.g. an overflow
        // of 24h00m01s with a 24h duration truncated to 24h needs ceil(24/24)=1
        // window, when the true overflow already needs a 2nd one.
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

    /**
     * Marks (once per day) and returns whether this is the player's first vote of
     * the day AND at least one daily bonus is configured — the fly coupon or the
     * daily-reward commands. Shared gate for both: the date is only claimed when
     * there's something to grant, so nothing fires when both are unset.
     */

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
        // Try the raw name first (Java accounts), then the Bedrock (Geyser/
        // Floodgate) name variants — a voter types their gamertag on the list,
        // but their in-game name carries the Floodgate prefix (and spaces may
        // be replaced), so an exact match on the raw name misses Bedrock voters.
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
        /** Only remember downtime younger than this — no streak can be older. */
        private static final long RETENTION_DAYS = 90L;
        private static final int MAX_ENTRIES = 100;

        private final JavaPlugin plugin;
        private final java.io.File heartbeatFile;
        private final java.io.File windowsFile;
        private final java.util.List<long[]> windows = new java.util.ArrayList<>();

        DowntimeTracker(@NotNull JavaPlugin plugin) {
            this.plugin = plugin;
            java.io.File dir = plugin.getDataFolder();
            if (!dir.exists()) dir.mkdirs();
            this.heartbeatFile = new java.io.File(dir, "uptime-heartbeat.txt");
            this.windowsFile = new java.io.File(dir, "downtime-windows.txt");
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
                        + " min downtime window — streaks that overlap it will be forgiven");
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
                String content = java.nio.file.Files.readString(heartbeatFile.toPath()).trim();
                return content.isEmpty() ? 0L : Long.parseLong(content);
            } catch (Exception ex) {
                return 0L;
            }
        }

        private void writeHeartbeat(long epochSecond) {
            try {
                java.nio.file.Files.writeString(heartbeatFile.toPath(), Long.toString(epochSecond));
            } catch (Exception ex) {
                plugin.getLogger().log(Level.FINE,
                        () -> "[vote-uptime] failed to write heartbeat: " + ex.getMessage());
            }
        }

        private void loadWindows() {
            if (!windowsFile.exists()) return;
            try {
                for (String line : java.nio.file.Files.readAllLines(windowsFile.toPath())) {
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
                    } catch (NumberFormatException ignored) {
                        // Skip malformed line — don't fail startup on a corrupt file.
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
                    java.nio.file.Files.writeString(windowsFile.toPath(), sb.toString());
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.FINE,
                            () -> "[vote-uptime] failed to save downtime windows: " + ex.getMessage());
                }
            }
        }
    }
}
