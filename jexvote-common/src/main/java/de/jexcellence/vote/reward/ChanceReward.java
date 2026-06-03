package de.jexcellence.vote.reward;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jextranslate.R18nManager;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Grants a nested reward with a configurable probability ("Lucky Vote").
 *
 * <p>On each grant the reward rolls against {@code chance} (0.0–1.0). On a hit the
 * nested reward is granted and, if {@code announce} is set, an i18n message is sent
 * to the player. When {@code show-chance} is enabled the {@code {chance}} placeholder
 * resolves to the configured percentage; otherwise it resolves to an empty string.
 *
 * @author JExcellence
 */
public class ChanceReward extends AbstractReward {

    public static final String TYPE_ID = "chance";

    @JsonProperty("id") private final String id;
    @JsonProperty("chance") private final double chance;
    @JsonProperty("reward") private final AbstractReward reward;
    @JsonProperty("announce") private final String announceKey;
    @JsonProperty("show-chance") private final boolean showChance;

    @JsonCreator
    public ChanceReward(@JsonProperty("id") @Nullable String id,
                        @JsonProperty("chance") double chance,
                        @JsonProperty("reward") @NotNull AbstractReward reward,
                        @JsonProperty("announce") @Nullable String announceKey,
                        @JsonProperty("show-chance") boolean showChance) {
        super(TYPE_ID);
        this.id = id;
        this.chance = clamp(chance);
        this.reward = reward;
        this.announceKey = announceKey;
        this.showChance = showChance;
    }

    private static double clamp(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    @Override
    public @NotNull CompletableFuture<Boolean> grant(@NotNull Player player) {
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return CompletableFuture.completedFuture(false);
        }
        return reward.grant(player).thenApply(success -> {
            if (Boolean.TRUE.equals(success)) {
                RewardStats.record(id);
                if (announceKey != null && !announceKey.isBlank()) {
                    announce(player);
                }
            }
            return success;
        });
    }

    private void announce(@NotNull Player player) {
        R18nManager.getInstance().msg(announceKey)
                .with("chance", showChance ? formatPercent(chance) + "%" : "")
                .send(player);
    }

    private static String formatPercent(double chance) {
        double percent = chance * 100.0;
        if (percent == Math.floor(percent)) {
            return String.valueOf((long) percent);
        }
        return String.valueOf(percent);
    }

    public double getChance() {
        return chance;
    }

    public @NotNull AbstractReward getReward() {
        return reward;
    }

    @Override
    public @NotNull String descriptionKey() {
        return "reward.chance";
    }

    @Override
    public double estimatedValue() {
        return reward.estimatedValue() * chance;
    }
}
