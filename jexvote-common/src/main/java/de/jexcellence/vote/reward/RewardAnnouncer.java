package de.jexcellence.vote.reward;

import de.jexcellence.jexplatform.reward.AbstractReward;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Static bridge that lets deserialized reward POJOs ({@link ChanceReward}, {@link LuckyReward}) post the public
 * win card for an announced prize without holding a service reference. The owning plugin installs the target
 * during enable and clears it on disable.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public final class RewardAnnouncer {

    /** Receives one announced win. */
    @FunctionalInterface
    public interface Target {

        /**
         * Posts the public card of an announced prize.
         *
         * @param winner  the player who won
         * @param reward  the prize
         * @param percent the drop chance, scaled to 0-100
         * @param shared  whether the winner shares rewards publicly, read on the winner's thread
         */
        void announce(@NotNull Player winner, @NotNull AbstractReward reward, double percent, boolean shared);
    }

    private static final Target NONE = (winner, reward, percent, shared) -> {
        // No public card until the plugin installs a target.
    };

    private static final AtomicReference<Target> target = new AtomicReference<>(NONE);

    private RewardAnnouncer() {
    }

    /**
     * Installs the target invoked for every announced win.
     *
     * @param announcer the target
     */
    public static void install(@NotNull Target announcer) {
        target.set(announcer);
    }

    /** Restores the no-op target (called on plugin disable). */
    public static void reset() {
        target.set(NONE);
    }

    /**
     * Posts the public card of an announced prize.
     *
     * @param winner  the player who won
     * @param reward  the prize
     * @param percent the drop chance, scaled to 0-100
     * @param shared  whether the winner shares rewards publicly
     */
    public static void announce(@NotNull Player winner, @NotNull AbstractReward reward, double percent,
                                boolean shared) {
        target.get().announce(winner, reward, percent, shared);
    }
}
