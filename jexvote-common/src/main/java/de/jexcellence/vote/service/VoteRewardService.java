package de.jexcellence.vote.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.vote.reward.LuckyReward;
import de.jexcellence.vote.view.VoteRewardDescriber;
import de.jexcellence.jexplatform.reward.RewardRegistry;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VoteRewardService {

    private static final String REWARDS_KEY = "rewards";
    private static final String COMMANDS_KEY = "commands";
    private static final String TYPE_KEY = "type";
    private static final String SOURCE_PARTY = "party";
    private static final String SOURCE_SHOP = "shop";
    private static final String SOURCE_STREAK_CLAIM = "streak-claim";

    private final Logger logger;
    private final ObjectMapper objectMapper;

    private final AtomicReference<List<AbstractReward>> defaultRewards;
    private final AtomicReference<List<AbstractReward>> guaranteedRewards;
    private final AtomicReference<Map<Integer, List<AbstractReward>>> streakRewards;
    private final AtomicReference<Map<String, List<AbstractReward>>> siteRewards;
    private final AtomicReference<List<String>> commandsOnVote;
    private final MultiplierService multiplierService;
    private volatile boolean manualStreakClaim;
    private volatile boolean streaksEnabled = true;
    private final AtomicReference<UnaryOperator<String>> siteIdResolver = new AtomicReference<>(service -> null);
    private final AtomicReference<LeftoverQueue> leftoverQueue = new AtomicReference<>();
    private final PlayerRewardChain chain;
    private final Set<String> reportedUnreadable = ConcurrentHashMap.newKeySet();

    @SuppressWarnings("java:S107")
    public VoteRewardService(@NotNull Logger logger,
                             @NotNull RewardRegistry rewardRegistry,
                             @NotNull List<AbstractReward> defaultRewards,
                             @NotNull List<AbstractReward> guaranteedRewards,
                             @NotNull Map<Integer, List<AbstractReward>> streakRewards,
                             @NotNull Map<String, List<AbstractReward>> siteRewards,
                             @NotNull List<String> commandsOnVote,
                             @NotNull MultiplierService multiplierService) {
        this.logger = logger;
        this.objectMapper = buildRewardMapper(rewardRegistry);
        this.defaultRewards = new AtomicReference<>(defaultRewards);
        this.guaranteedRewards = new AtomicReference<>(guaranteedRewards);
        this.streakRewards = new AtomicReference<>(streakRewards);
        this.siteRewards = new AtomicReference<>(siteRewards);
        this.commandsOnVote = new AtomicReference<>(commandsOnVote);
        this.multiplierService = multiplierService;
        this.chain = new PlayerRewardChain(logger);
    }

    public void reloadRewards(@NotNull List<AbstractReward> defaultRewards,
                              @NotNull List<AbstractReward> guaranteedRewards,
                              @NotNull Map<Integer, List<AbstractReward>> streakRewards,
                              @NotNull Map<String, List<AbstractReward>> siteRewards,
                              @NotNull List<String> commandsOnVote) {
        this.defaultRewards.set(defaultRewards);
        this.guaranteedRewards.set(guaranteedRewards);
        this.streakRewards.set(streakRewards);
        this.siteRewards.set(siteRewards);
        this.commandsOnVote.set(commandsOnVote);
    }

    /**
     * Sets how a vote's service name maps to the id of its site in {@code sites.yml}, so {@code site-rewards}
     * keyed by site id apply.
     *
     * @param resolver service name to site id, returning {@code null} when no site matches
     */
    public void setSiteIdResolver(@NotNull UnaryOperator<String> resolver) {
        siteIdResolver.set(resolver);
    }

    /**
     * The extra rewards of the site a vote came from.
     *
     * @param serviceName the service name a vote arrived with
     * @return the {@code site-rewards} of that site, keyed by service name or site id
     */
    public @NotNull List<AbstractReward> siteRewardsFor(@NotNull String serviceName) {
        return SiteRewardLookup.find(siteRewards.get(), serviceName, siteIdResolver.get().apply(serviceName));
    }

    private static @NotNull ObjectMapper buildRewardMapper(@NotNull RewardRegistry registry) {
        var mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        registry.types().forEach((name, type) ->
                mapper.registerSubtypes(new NamedType(type.implementationClass(), type.id())));
        return mapper;
    }

    /**
     * Grants all applicable rewards to a player for a vote.
     *
     * @param player       the player to reward
     * @param serviceName  the voting service name
     * @param currentStreak the current vote streak
     */
    public @NotNull CompletableFuture<Void> grantRewards(@NotNull Player player, @NotNull String serviceName,
                                                         int currentStreak) {
        return grantAll(rewardsFor(serviceName, currentStreak), player, multiplierService.current(), serviceName)
                .thenApply(tally -> null);
    }

    /** The rewards one vote pays: default, guaranteed, site and (unless claimed by hand) streak rewards. */
    private @NotNull List<AbstractReward> rewardsFor(@NotNull String serviceName, int currentStreak) {
        List<AbstractReward> rewards = new ArrayList<>(defaultRewards.get());
        rewards.addAll(guaranteedRewards.get());
        rewards.addAll(siteRewardsFor(serviceName));
        if (streaksEnabled && !manualStreakClaim) {
            rewards.addAll(streakRewards.get().getOrDefault(currentStreak, List.of()));
        }
        return rewards;
    }

    /**
     * Runs the configured {@code commands-on-vote} for an online voter. Call it once the vote's rewards arrived.
     *
     * @param player        the voter, on their thread
     * @param serviceName   the voting service name
     * @param currentStreak the current vote streak
     */
    public void runVoteCommands(@NotNull Player player, @NotNull String serviceName, int currentStreak) {
        runCommands(player, resolveCommands(serviceName, currentStreak));
    }

    /**
     * Runs console commands for a player with {@code {player}} filled in. A failing command is logged and the rest
     * still run.
     *
     * @param player   the player the commands are for
     * @param commands the commands, {@code {player}} not yet filled in
     */
    public void runCommands(@NotNull Player player, @NotNull List<String> commands) {
        final String playerName = player.getName();
        for (String command : commands) {
            String resolved = command.replace("{player}", playerName);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
            } catch (Exception ex) {
                logger.log(Level.WARNING, ex, () -> "Vote command failed for " + playerName + ": " + resolved);
            }
        }
    }

    /**
     * Serializes the vote's console commands alone (no rewards), for a voter who left before they ran.
     *
     * @return the stored blob, or {@code null} when serialization failed
     */
    public @Nullable String serializeVoteCommands(@NotNull String serviceName, int currentStreak,
                                                  @NotNull List<String> additionalCommands) {
        List<String> commands = new ArrayList<>(resolveCommands(serviceName, currentStreak));
        commands.addAll(additionalCommands);
        return serializeStored(List.of(), commands);
    }

    /**
     * Grants the rewards one after another on the player's thread (see {@link PlayerRewardChain}). Rewards a player
     * who left never received are kept as a pending row under {@code source} for the next join.
     *
     * @return how many rewards were delivered or kept, and how many failed
     */
    @NotNull CompletableFuture<GrantTally> grantAll(@NotNull List<AbstractReward> rewards, @NotNull Player player,
                                                   double multiplier, @NotNull String source) {
        List<AbstractReward> scaled = rewards.stream().map(reward -> scaledReward(reward, multiplier)).toList();
        AtomicInteger failed = new AtomicInteger();
        return chain.grantInOrder(player, scaled, reward -> reward.grant(player), AbstractReward::typeId,
                (reward, granted) -> {
                    if (!Boolean.TRUE.equals(granted)) {
                        failed.incrementAndGet();
                        logUndelivered(player, reward.typeId(), granted);
                    }
                }).thenApply(notReached -> {
                    int lost = keepForNextJoin(player, source, notReached);
                    int failedTotal = failed.get() + lost;
                    return new GrantTally(scaled.size() - failedTotal, failedTotal);
                });
    }

    /**
     * How a list of rewards went: delivered (or kept for the next join because the player left) and failed.
     *
     * @param granted rewards the player has or will get on the next join
     * @param failed  rewards whose grant failed
     */
    public record GrantTally(int granted, int failed) {
    }

    /** Where rewards a player left before receiving are kept: a pending row delivered on the next join. */
    @FunctionalInterface
    public interface LeftoverQueue {
        void keep(@NotNull UUID player, @NotNull String source, @NotNull String rewardData);
    }

    /**
     * Wires the pending queue for rewards a player left before receiving.
     *
     * @param queue stores a pending row
     */
    public void setLeftoverQueue(@NotNull LeftoverQueue queue) {
        leftoverQueue.set(queue);
    }

    /** Keeps the rewards a player left before receiving; returns how many could not be kept. */
    private int keepForNextJoin(@NotNull Player player, @NotNull String source,
                                @NotNull List<AbstractReward> notReached) {
        if (notReached.isEmpty()) {
            return 0;
        }
        String data = serializeRewardList(notReached);
        LeftoverQueue queue = leftoverQueue.get();
        final String playerName = player.getName();
        final int count = notReached.size();
        if (data == null || queue == null) {
            logger.log(Level.SEVERE, () -> String.format(
                    "%d vote reward(s) for %s could not be kept after they left: %s", count, playerName, notReached));
            return count;
        }
        queue.keep(player.getUniqueId(), source, data);
        logger.log(Level.INFO, () -> String.format(
                "%s left during a payout - %d vote reward(s) kept for the next join", playerName, count));
        return 0;
    }

    private void logUndelivered(@NotNull Player player, @NotNull String typeId, @Nullable Boolean granted) {
        if (Boolean.FALSE.equals(granted)) {
            final String playerName = player.getName();
            logger.log(Level.WARNING, () -> String.format("Vote %s reward did not deliver to %s", typeId, playerName));
        }
    }

    /**
     * Wires the scheduler that runs every reward grant on the player's thread.
     *
     * @param scheduler the plugin's platform scheduler
     */
    public void setPlayerScheduler(@NotNull PlatformScheduler scheduler) {
        setPlayerThread(scheduler::runAtEntity);
    }

    void setPlayerThread(@NotNull PlayerRewardChain.PlayerThread thread) {
        chain.setPlayerThread(thread);
    }

    /**
     * Returns a copy of {@code reward} with its top-level numeric {@code amount}
     * scaled by {@code multiplier} (covers xp/currency). Returns the original when
     * no scaling applies or scaling fails. Amounts nested inside composite/chance/
     * lucky rewards are not scaled.
     */
    private @NotNull AbstractReward scaledReward(@NotNull AbstractReward reward, double multiplier) {
        if (multiplier == 1.0) {
            return reward;
        }
        try {
            Map<String, Object> map = serializeReward(reward);
            if (scaleAmountInPlace(map, multiplier)) {
                String json = objectMapper.writeValueAsString(map);
                return objectMapper.readValue(json, AbstractReward.class);
            }
        } catch (Exception e) {
            logger.log(Level.FINE, e, () -> "Failed to scale reward amount; granting unscaled");
        }
        return reward;
    }

    private static boolean scaleAmountInPlace(@NotNull Map<String, Object> map, double multiplier) {
        Object amount = map.get("amount");
        if (amount instanceof Number number) {
            map.put("amount", number.doubleValue() * multiplier);
            return true;
        }
        return false;
    }

    /**
     * Serializes rewards for offline players.
     *
     * @param serviceName  the voting service name
     * @param currentStreak the current vote streak
     * @return JSON string of rewards, or null if serialization failed
     */
    public @Nullable String serializeRewards(@NotNull String serviceName, int currentStreak,
                                             @NotNull List<String> additionalCommands) {
        double multiplier = multiplierService.current();
        List<Map<String, Object>> rewardList = rewardsFor(serviceName, currentStreak).stream()
                .map(reward -> serializeScaled(reward, multiplier))
                .toList();
        List<String> commands = new ArrayList<>(resolveCommands(serviceName, currentStreak));
        commands.addAll(additionalCommands);
        return serializeStored(rewardList, commands);
    }

    /**
     * Grants an explicit reward list to a player (used by Vote Parties).
     * No vote multiplier is applied - party rewards are granted as configured.
     */
    public void grantRewardList(@NotNull Player player, @NotNull List<AbstractReward> rewards) {
        if (!heldForNormalProfile(player, rewards, SOURCE_PARTY)) {
            grantAll(rewards, player, 1.0, SOURCE_PARTY);
        }
    }

    /**
     * Keeps rewards for the player's JExOneblock Normal profile while the Season profile is active (it cannot use
     * vote keys, coupons or items) and tells the player where they went.
     *
     * @return {@code true} when the rewards were kept instead of granted
     */
    private boolean heldForNormalProfile(@NotNull Player player, @NotNull List<AbstractReward> rewards,
                                         @NotNull String source) {
        NormalProfileRewards normalProfile = NormalProfileRewards.current();
        if (rewards.isEmpty() || normalProfile == null || !normalProfile.routes(player.getUniqueId())) {
            return false;
        }
        String data = serializeRewardList(rewards);
        if (data == null) {
            return false;
        }
        normalProfile.hold(player.getUniqueId(), source, data);
        normalProfile.tellWhere(player);
        return true;
    }

    /**
     * Grants a single reward and reports whether it actually delivered, so a caller
     * (the shop) can refund on failure instead of charging fire-and-forget. Any
     * exception resolves to {@code false} (already logged).
     */
    public @NotNull CompletableFuture<Boolean> grantChecked(@NotNull Player player,
                                                            @NotNull AbstractReward reward) {
        if (heldForNormalProfile(player, List.of(reward), SOURCE_SHOP)) {
            return CompletableFuture.completedFuture(true);
        }
        return reward.grant(player).exceptionally(ex -> {
            final String playerName = player.getName();
            logger.log(Level.WARNING, ex, () -> "Failed to grant shop reward to " + playerName);
            return false;
        });
    }

    /**
     * Serializes an explicit reward list for offline delivery (used by Vote Parties).
     *
     * @return JSON string, or {@code null} if serialization failed
     */
    public @Nullable String serializeRewardList(@NotNull List<AbstractReward> rewards) {
        List<Map<String, Object>> rewardList = new ArrayList<>();
        rewards.forEach(reward -> rewardList.add(serializeReward(reward)));
        return serializeStored(rewardList, List.of());
    }

    private @Nullable String serializeStored(@NotNull List<Map<String, Object>> rewardList,
                                             @NotNull List<String> commands) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(REWARDS_KEY, rewardList);
            data.put(COMMANDS_KEY, commands);
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            logger.log(Level.WARNING, e, () -> "Failed to serialize stored vote rewards");
            return null;
        }
    }

    /**
     * Grants a stored reward blob and reports what the player actually received.
     *
     * <p>The descriptions are the point. Telling somebody "you had 3 pending rewards"
     * says nothing about what arrived, and for a jackpot the pool cannot answer that
     * at all - only the draw can. So each reward is described from its outcome, and a
     * lucky pool reports the prize it rolled rather than the fact that it rolled.
     * The rewards are granted one after another on the player's thread, as in {@link #grantAll}; the stored console
     * commands run after them, like an online vote's commands, and stay stored when the player left before them.
     *
     * @param player     the recipient
     * @param rewardData the serialised blob
     * @return what arrived (one MiniMessage fragment per reward granted) and what is left: the rewards of a player
     *         who went offline during the payout, a reward that could not be read, or the whole blob when it could not
     *         be read at all
     */
    @SuppressWarnings("unchecked")
    public @NotNull CompletableFuture<StoredGrant> grantSerializedRewards(
            @NotNull Player player, @NotNull String rewardData) {
        try {
            Map<String, Object> data = objectMapper.readValue(rewardData, Map.class);
            List<Map<String, Object>> rewards = (List<Map<String, Object>>) data.get(REWARDS_KEY);
            List<String> commands = (List<String>) data.get(COMMANDS_KEY);

            List<String> storedCommands = commands == null ? List.of() : List.copyOf(commands);
            final List<String> descriptions = new ArrayList<>();
            List<Map<String, Object>> stored = rewards == null ? List.of() : rewards;
            return chain.grantInOrder(player, stored, rewardMap -> grantSingleReward(player, rewardMap),
                    rewardMap -> rewardMap.get(TYPE_KEY), (rewardMap, description) -> {
                        if (description != null) {
                            descriptions.add(description);
                        }
                    }).thenCompose(notReached -> runStoredCommands(player, storedCommands)
                            .thenApply(commandsLeft -> new StoredGrant(List.copyOf(descriptions),
                                    remainingBlob(player, notReached, commandsLeft))));
        } catch (Exception e) {
            reportUnreadable(player, rewardData, e);
            return CompletableFuture.completedFuture(new StoredGrant(List.of(), rewardData));
        }
    }

    /** Runs stored commands on the player's thread; returns them when the player left first, else an empty list. */
    private @NotNull CompletableFuture<List<String>> runStoredCommands(@NotNull Player player,
                                                                      @NotNull List<String> commands) {
        if (commands.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return chain.grantInOrder(player, List.of(commands), batch -> {
            runCommands(player, batch);
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }, batch -> COMMANDS_KEY, (batch, ran) -> logger.log(Level.FINE, () -> "Stored vote commands ran for "
                + player.getName())).thenApply(notRun -> notRun.isEmpty() ? List.of() : commands);
    }

    private @Nullable String remainingBlob(@NotNull Player player, @NotNull List<Map<String, Object>> rewards,
                                           @NotNull List<String> commands) {
        if (rewards.isEmpty() && commands.isEmpty()) {
            return null;
        }
        String blob = serializeStored(rewards, commands);
        if (blob == null) {
            final String playerName = player.getName();
            logger.log(Level.SEVERE, () -> String.format("Undelivered vote rewards of %s could not be stored again - "
                    + "restore them by hand: rewards %s, commands %s", playerName, rewards, commands));
        }
        return blob;
    }

    /** Logs an unreadable stored blob at SEVERE once per server run; later joins log it at FINE. */
    private void reportUnreadable(@NotNull Player player, @NotNull String rewardData, @NotNull Exception failure) {
        final String playerName = player.getName();
        Level level = reportedUnreadable.add(rewardData) ? Level.SEVERE : Level.FINE;
        logger.log(level, failure, () -> "Stored vote rewards of " + playerName
                + " could not be read - kept for the next join: " + rewardData);
    }

    /**
     * The outcome of a stored reward blob.
     *
     * @param received  one MiniMessage fragment per reward granted
     * @param remaining the rewards the player left before receiving, as a new blob; {@code null} when none
     */
    public record StoredGrant(@NotNull List<String> received, @Nullable String remaining) {
    }

    /**
     * Grants one reward and describes what it gave.
     *
     * @return a future resolving to the description, {@code null} when the reward did not grant, or failing with
     *         {@link PlayerRewardChain.KeepForLaterException} when it could not be read, so it is kept for the next
     *         join
     */
    private @NotNull CompletableFuture<String> grantSingleReward(@NotNull Player player,
                                                                 @NotNull Map<String, Object> rewardMap) {
        try {
            String json = objectMapper.writeValueAsString(rewardMap);
            AbstractReward reward = objectMapper.readValue(json, AbstractReward.class);

            if (reward instanceof LuckyReward lucky) {
                return lucky.grantAndReport(player).thenApply(won -> {
                    if (won == null) {
                        logUndelivered(player, lucky.typeId(), false);
                        return null;
                    }
                    return VoteRewardDescriber.describeLuckyWin(won, player);
                });
            }
            return reward.grant(player).thenApply(success -> {
                logUndelivered(player, reward.typeId(), success);
                return Boolean.TRUE.equals(success) ? VoteRewardDescriber.describe(reward, player) : null;
            });
        } catch (Exception ex) {
            Object typeId = rewardMap.get(TYPE_KEY);
            logger.log(Level.SEVERE, ex, () -> String.format(
                    "Stored vote reward of type %s could not be read - kept for the next join", typeId));
            return CompletableFuture.failedFuture(new PlayerRewardChain.KeepForLaterException("unreadable reward"));
        }
    }

    private @NotNull Map<String, Object> serializeScaled(@NotNull AbstractReward reward, double multiplier) {
        Map<String, Object> map = serializeReward(reward);
        if (multiplier != 1.0) {
            scaleAmountInPlace(map, multiplier);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private @NotNull Map<String, Object> serializeReward(@NotNull AbstractReward reward) {
        try {
            String json = objectMapper.writeValueAsString(reward);
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            logger.log(Level.WARNING, e, () -> "Failed to serialize reward: " + reward.typeId());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put(TYPE_KEY, reward.typeId());
            return fallback;
        }
    }

    private @NotNull List<String> resolveCommands(@NotNull String serviceName, int currentStreak) {
        return VoteCommandSet.fill(commandsOnVote.get(), serviceName, currentStreak);
    }

    public @NotNull List<AbstractReward> getDefaultRewards() {
        return defaultRewards.get();
    }

    /** The rewards granted on every vote in addition to the default rewards. */
    public @NotNull List<AbstractReward> getGuaranteedRewards() {
        return guaranteedRewards.get();
    }

    public boolean hasGuaranteedRewards() {
        return !guaranteedRewards.get().isEmpty();
    }

    public @NotNull Map<Integer, List<AbstractReward>> getStreakRewards() {
        return streakRewards.get();
    }

    public boolean isManualStreakClaim() {
        return manualStreakClaim;
    }

    public void setManualStreakClaim(boolean manualStreakClaim) {
        this.manualStreakClaim = manualStreakClaim;
    }

    public void setStreaksEnabled(boolean streaksEnabled) {
        this.streaksEnabled = streaksEnabled;
    }

    /**
     * Grants streak rewards for a specific milestone day. Used by the claim GUI
     * when {@code manualStreakClaim} is enabled, one after another on the player's thread.
     *
     * @param player       the player to reward
     * @param milestoneDay the milestone day to claim
     * @return how many rewards were delivered (or kept for the next join) and how many failed; nothing granted when
     *         the milestone has no rewards
     */
    public @NotNull CompletableFuture<GrantTally> grantStreakReward(@NotNull Player player, int milestoneDay) {
        List<AbstractReward> rewards = streakRewards.get().get(milestoneDay);
        if (rewards == null || rewards.isEmpty()) {
            return CompletableFuture.completedFuture(new GrantTally(0, 0));
        }
        if (heldForNormalProfile(player, rewards, SOURCE_STREAK_CLAIM)) {
            return CompletableFuture.completedFuture(new GrantTally(rewards.size(), 0));
        }
        return grantAll(rewards, player, 1.0, SOURCE_STREAK_CLAIM);
    }
}
