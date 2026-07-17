package de.jexcellence.vote.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.reward.RewardRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VoteRewardService {

    private static final String REWARDS_KEY = "rewards";
    private static final String COMMANDS_KEY = "commands";

    private final Logger logger;
    private final ObjectMapper objectMapper;

    private final List<AbstractReward> defaultRewards;
    private final List<AbstractReward> guaranteedRewards;
    private final Map<Integer, List<AbstractReward>> streakRewards;
    private final Map<String, List<AbstractReward>> siteRewards;
    private final List<String> commandsOnVote;
    private final MultiplierService multiplierService;
    private volatile boolean manualStreakClaim;
    private volatile boolean streaksEnabled = true;

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
        this.defaultRewards = defaultRewards;
        this.guaranteedRewards = guaranteedRewards;
        this.streakRewards = streakRewards;
        this.siteRewards = siteRewards;
        this.commandsOnVote = commandsOnVote;
        this.multiplierService = multiplierService;
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
    public void grantRewards(@NotNull Player player, @NotNull String serviceName, int currentStreak) {
        double multiplier = multiplierService.current();

        grantAll(defaultRewards, player, multiplier);
        // Granted on EVERY vote, in addition to the weighted default-pool pick above.
        grantAll(guaranteedRewards, player, multiplier);
        grantAll(siteRewards.getOrDefault(serviceName.toLowerCase(), List.of()), player, multiplier);

        if (streaksEnabled && !manualStreakClaim) {
            grantAll(streakRewards.getOrDefault(currentStreak, List.of()), player, multiplier);
        }

        // Commands
        commandsOnVote.forEach(command -> {
            String resolved = command
                    .replace("{player}", player.getName())
                    .replace("{service}", serviceName)
                    .replace("{streak}", String.valueOf(currentStreak));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
        });
    }

    private void grantAll(@NotNull List<AbstractReward> rewards, @NotNull Player player, double multiplier) {
        for (AbstractReward reward : rewards) {
            scaledReward(reward, multiplier).grant(player).exceptionally(ex -> {
                logger.log(Level.WARNING, "Failed to grant reward to " + player.getName(), ex);
                return false;
            });
        }
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
    public @Nullable String serializeRewards(@NotNull String serviceName, int currentStreak) {
        try {
            double multiplier = multiplierService.current();
            List<Map<String, Object>> rewardList = new ArrayList<>();

            defaultRewards.forEach(reward -> rewardList.add(serializeScaled(reward, multiplier)));
            // Queue the guaranteed rewards too, so offline voters get them on next login.
            guaranteedRewards.forEach(reward -> rewardList.add(serializeScaled(reward, multiplier)));

            siteRewards.getOrDefault(serviceName.toLowerCase(), List.of())
                    .forEach(reward -> rewardList.add(serializeScaled(reward, multiplier)));

            if (streaksEnabled && !manualStreakClaim) {
                streakRewards.getOrDefault(currentStreak, List.of())
                        .forEach(reward -> rewardList.add(serializeScaled(reward, multiplier)));
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put(REWARDS_KEY, rewardList);
            data.put(COMMANDS_KEY, resolveCommands(serviceName, currentStreak));

            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            logger.log(Level.WARNING, "Failed to serialize vote rewards", e);
            return null;
        }
    }

    /**
     * Grants an explicit reward list to a player (used by Vote Parties).
     * No vote multiplier is applied — party rewards are granted as configured.
     */
    public void grantRewardList(@NotNull Player player, @NotNull List<AbstractReward> rewards) {
        grantAll(rewards, player, 1.0);
    }

    /**
     * Serializes an explicit reward list for offline delivery (used by Vote Parties).
     *
     * @return JSON string, or {@code null} if serialization failed
     */
    public @Nullable String serializeRewardList(@NotNull List<AbstractReward> rewards) {
        try {
            List<Map<String, Object>> rewardList = new ArrayList<>();
            rewards.forEach(reward -> rewardList.add(serializeReward(reward)));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put(REWARDS_KEY, rewardList);
            data.put(COMMANDS_KEY, Collections.emptyList());
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            logger.log(Level.WARNING, "Failed to serialize party rewards", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public void grantSerializedRewards(@NotNull Player player, @NotNull String rewardData) {
        try {
            Map<String, Object> data = objectMapper.readValue(rewardData, Map.class);
            List<Map<String, Object>> rewards = (List<Map<String, Object>>) data.get(REWARDS_KEY);
            List<String> commands = (List<String>) data.get(COMMANDS_KEY);

            if (rewards != null) {
                for (Map<String, Object> rewardMap : rewards) {
                    grantSingleReward(player, rewardMap);
                }
            }

            if (commands != null) {
                for (String command : commands) {
                    String resolved = command.replace("{player}", player.getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, e, () -> String.format("Failed to grant serialized rewards to %s", player.getName()));
        }
    }

    private void grantSingleReward(@NotNull Player player, @NotNull Map<String, Object> rewardMap) {
        try {
            String json = objectMapper.writeValueAsString(rewardMap);
            AbstractReward reward = objectMapper.readValue(json, AbstractReward.class);
            reward.grant(player);
        } catch (Exception ex) {
            String typeId = (String) rewardMap.get("type");
            logger.log(Level.WARNING, ex, () -> String.format("Failed to deserialize reward type: %s", typeId));
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
            logger.log(Level.WARNING, "Failed to serialize reward: " + reward.typeId(), e);
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("type", reward.typeId());
            return fallback;
        }
    }

    private @NotNull List<String> resolveCommands(@NotNull String serviceName, int currentStreak) {
        if (commandsOnVote.isEmpty()) return Collections.emptyList();

        List<String> resolved = new ArrayList<>(commandsOnVote.size());
        for (String command : commandsOnVote) {
            resolved.add(command
                    .replace("{service}", serviceName)
                    .replace("{streak}", String.valueOf(currentStreak)));
        }
        return resolved;
    }

    public @NotNull List<AbstractReward> getDefaultRewards() {
        return defaultRewards;
    }

    /**
     * @return {@code true} when at least one guaranteed reward is configured
     * (used to decide whether to send the guaranteed-reward notification).
     */
    public boolean hasGuaranteedRewards() {
        return !guaranteedRewards.isEmpty();
    }

    public @NotNull Map<Integer, List<AbstractReward>> getStreakRewards() {
        return streakRewards;
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
     * when {@code manualStreakClaim} is enabled.
     *
     * @param player       the player to reward
     * @param milestoneDay the milestone day to claim
     * @return a future that completes with {@code true} if all rewards were granted
     */
    public @NotNull CompletableFuture<Boolean> grantStreakReward(@NotNull Player player, int milestoneDay) {
        List<AbstractReward> rewards = streakRewards.get(milestoneDay);
        if (rewards == null || rewards.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (AbstractReward reward : rewards) {
            futures.add(reward.grant(player).exceptionally(ex -> {
                logger.log(Level.WARNING, ex, () -> String.format(
                        "Failed to grant streak reward (day %d) to %s", milestoneDay, player.getName()));
                return false;
            }));
        }

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(v -> futures.stream().allMatch(f -> Boolean.TRUE.equals(f.join())));
    }
}
