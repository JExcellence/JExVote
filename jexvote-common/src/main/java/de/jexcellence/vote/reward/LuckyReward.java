package de.jexcellence.vote.reward;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jextranslate.R18nManager;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Grants exactly one reward drawn from a weighted pool ("Lucky Vote" jackpot).
 *
 * <p>Each {@link Entry} has a relative {@code weight}; the higher the weight, the
 * more likely it is selected. Exactly one entry is granted per invocation. An
 * optional per-entry {@code announce} i18n key is sent on a successful grant.
 *
 * @author JExcellence
 */
public class LuckyReward extends AbstractReward {

    public static final String TYPE_ID = "lucky";

    /** A single weighted outcome in the pool. */
    public static class Entry {

        @JsonProperty("weight") private final double weight;
        @JsonProperty("reward") private final AbstractReward reward;
        @JsonProperty("announce") private final String announceKey;

        @JsonCreator
        public Entry(@JsonProperty("weight") double weight,
                     @JsonProperty("reward") @NotNull AbstractReward reward,
                     @JsonProperty("announce") @Nullable String announceKey) {
            this.weight = weight <= 0.0 ? 1.0 : weight;
            this.reward = reward;
            this.announceKey = announceKey;
        }

        public double weight() {
            return weight;
        }

        public @NotNull AbstractReward reward() {
            return reward;
        }

        public @Nullable String announceKey() {
            return announceKey;
        }
    }

    @JsonProperty("entries") private final List<Entry> entries;

    @JsonCreator
    public LuckyReward(@JsonProperty("entries") @NotNull List<Entry> entries) {
        super(TYPE_ID);
        this.entries = List.copyOf(entries);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> grant(@NotNull Player player) {
        if (entries.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        Entry chosen = pickWeighted();
        return chosen.reward().grant(player).thenApply(success -> {
            String key = chosen.announceKey();
            if (Boolean.TRUE.equals(success) && key != null && !key.isBlank()) {
                R18nManager.getInstance().msg(key).send(player);
            }
            return success;
        });
    }

    private @NotNull Entry pickWeighted() {
        double total = 0.0;
        for (Entry entry : entries) {
            total += entry.weight();
        }

        double roll = ThreadLocalRandom.current().nextDouble(total);
        double cursor = 0.0;
        for (Entry entry : entries) {
            cursor += entry.weight();
            if (roll < cursor) {
                return entry;
            }
        }
        return entries.get(entries.size() - 1);
    }

    public @NotNull List<Entry> getEntries() {
        return entries;
    }

    @Override
    public @NotNull String descriptionKey() {
        return "reward.lucky";
    }

    @Override
    public double estimatedValue() {
        double total = entries.stream().mapToDouble(Entry::weight).sum();
        if (total <= 0.0) {
            return 0.0;
        }
        return entries.stream()
                .mapToDouble(entry -> entry.reward().estimatedValue() * (entry.weight() / total))
                .sum();
    }
}
