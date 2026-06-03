package de.jexcellence.vote.reward;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Static bridge that lets deserialized reward POJOs ({@link ChanceReward},
 * {@link LuckyReward}) report a successful grant without holding a repository
 * reference. The owning plugin installs the recorder during enable and clears it
 * on disable.
 *
 * @author JExcellence
 */
public final class RewardStats {

    private static volatile Consumer<String> recorder = key -> {
        // No-op until the plugin installs a recorder.
    };

    private RewardStats() {
        // Static utility — no instances
    }

    /**
     * Installs the recorder invoked on each successful keyed grant.
     */
    public static void setRecorder(@NotNull Consumer<String> recorder) {
        RewardStats.recorder = recorder;
    }

    /**
     * Restores the no-op recorder (called on plugin disable).
     */
    public static void reset() {
        RewardStats.recorder = key -> {
            // No-op
        };
    }

    /**
     * Reports a successful grant of the reward identified by {@code key}.
     * Keys that are null or blank are ignored.
     */
    public static void record(@Nullable String key) {
        if (key != null && !key.isBlank()) {
            recorder.accept(key);
        }
    }
}
