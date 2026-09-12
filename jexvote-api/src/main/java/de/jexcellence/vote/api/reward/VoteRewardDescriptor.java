package de.jexcellence.vote.api.reward;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A platform-free description of one reward a {@link VoteRewardProvider} wants
 * JExVote to grant for a vote.
 *
 * <p>Third-party plugins depend only on this clean contract (Bukkit + this jar),
 * never on JExVote's internal reward framework - JExVote translates each
 * descriptor into a real grant through its existing, offline-aware reward
 * pipeline.
 *
 * <p>Sealed to the four shapes the vote pipeline can execute; match on the
 * record type (a switch pattern) to read the fields.
 *
 * @author JExcellence
 * @since 1.0.0
 */
public sealed interface VoteRewardDescriptor
        permits VoteRewardDescriptor.Item,
                VoteRewardDescriptor.Command,
                VoteRewardDescriptor.Currency,
                VoteRewardDescriptor.Points {

    /**
     * Give {@code amount} of {@code material}.
     *
     * @param material    the item material
     * @param amount      stack size (>= 1)
     * @param displayName optional display name (MiniMessage or plain), or {@code null} for the vanilla name
     */
    record Item(@NotNull Material material, int amount, @Nullable String displayName)
            implements VoteRewardDescriptor {
        public Item {
            if (amount < 1) {
                throw new IllegalArgumentException("item amount must be >= 1, was " + amount);
            }
        }
    }

    /**
     * Run a console command. {@code %player%} and {@code {player}} in the template
     * are replaced with the voter's name before dispatch.
     *
     * @param template the console command template (no leading slash)
     */
    record Command(@NotNull String template) implements VoteRewardDescriptor {}

    /**
     * Deposit {@code amount} of the currency identified by {@code currencyId}
     * through the host economy (JExEconomy, then Vault).
     *
     * @param currencyId the host currency id (e.g. {@code "coins"})
     * @param amount     the amount to deposit (>= 0)
     */
    record Currency(@NotNull String currencyId, double amount) implements VoteRewardDescriptor {
        public Currency {
            if (amount < 0.0) {
                throw new IllegalArgumentException("currency amount must be >= 0, was " + amount);
            }
        }
    }

    /**
     * Grant {@code amount} vote-points.
     *
     * @param amount the vote-points to add (>= 0)
     */
    record Points(int amount) implements VoteRewardDescriptor {
        public Points {
            if (amount < 0) {
                throw new IllegalArgumentException("points amount must be >= 0, was " + amount);
            }
        }
    }
}
