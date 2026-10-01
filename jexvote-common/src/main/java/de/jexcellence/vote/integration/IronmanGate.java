package de.jexcellence.vote.integration;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Soft gate to JExOneblock's Ironman isolation API. Every check answers "allowed" when JExOneblock is not
 * installed or not enabled yet, and the JExOneblock API classes are only touched through
 * {@link OneblockIronmanChecks} once the plugin is present, so this class loads without them.
 *
 * @author JExcellence
 * @since 2.1.0
 */
public final class IronmanGate {

    private static final Logger LOGGER = Logger.getLogger(IronmanGate.class.getName());
    private static final long RETRY_MS = 10_000L;
    private static final String PLUGIN = "JExOneblock";
    private static final IronmanGate SHARED = new IronmanGate();

    private volatile @Nullable IronmanChecks checks;
    private volatile long lastLookup;

    /** The plugin-wide gate instance. */
    public static @NotNull IronmanGate shared() {
        return SHARED;
    }

    /** Whether a transfer, gift or trade between the two players must be refused (Ironman isolation). */
    public boolean isTradeBlocked(@NotNull UUID first, @NotNull UUID second) {
        IronmanChecks current = resolve();
        return current != null && current.isTradeBlocked(first, second);
    }

    /** Whether the two players are on the same world-set (both Ironman or both Normal). */
    public boolean sameWorldSet(@NotNull UUID first, @NotNull UUID second) {
        IronmanChecks current = resolve();
        return current == null || current.sameWorldSet(first, second);
    }

    /** Stamps a minted redeemable item as issued to {@code owner}'s active profile. */
    public void stampOwner(@NotNull ItemStack item, @NotNull UUID owner) {
        IronmanChecks current = resolve();
        if (current != null) {
            current.stampOwner(item, owner);
        }
    }

    /**
     * The refusal for redeeming {@code item}: {@code null} when allowed, otherwise the translation key suffix
     * {@code ironman-not-owner} or {@code ironman-item}.
     */
    public @Nullable String itemRefusal(@NotNull ItemStack item, @NotNull UUID user) {
        IronmanChecks current = resolve();
        return current == null ? null : current.itemRefusal(item, user);
    }

    /**
     * Whether the player's active JExOneblock profile is the Season profile, which cannot use vote crate keys,
     * coupons or items; its vote rewards go to the {@link #rewardProfile reward profile}. Works for offline players.
     */
    public boolean isSeasonProfile(@NotNull UUID player) {
        IronmanChecks current = resolve();
        return current != null && current.isSeasonProfile(player);
    }

    /** The id of the player's active JExOneblock profile, or {@code null} when profiles are off or unknown. */
    public @Nullable Long activeProfileId(@NotNull UUID player) {
        IronmanChecks current = resolve();
        return current == null ? null : current.activeProfileId(player);
    }

    /** The player's lowest-slot Normal profile, which receives the vote rewards of the Season profile. */
    public @NotNull CompletableFuture<Optional<RewardProfile>> rewardProfile(@NotNull UUID player) {
        IronmanChecks current = resolve();
        return current == null ? CompletableFuture.completedFuture(Optional.empty()) : current.rewardProfile(player);
    }

    /** Deposits into a JExOneblock island bank; {@code false} when JExOneblock is missing or the island unknown. */
    public boolean depositToIslandBank(long islandId, @NotNull String currency, long amount) {
        IronmanChecks current = resolve();
        return current != null && current.depositToIslandBank(islandId, currency, amount);
    }

    private @Nullable IronmanChecks resolve() {
        IronmanChecks current = checks;
        if (current != null) {
            return current;
        }
        long now = System.currentTimeMillis();
        if (now - lastLookup < RETRY_MS) {
            return null;
        }
        lastLookup = now;
        if (Bukkit.getPluginManager().getPlugin(PLUGIN) == null) {
            return null;
        }
        try {
            current = OneblockIronmanChecks.lookup();
        } catch (LinkageError ex) {
            LOGGER.log(Level.FINE, () -> "JExOneblock API not on the classpath: " + ex.getMessage());
            current = null;
        }
        checks = current;
        return current;
    }
}
