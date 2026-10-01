package de.jexcellence.vote.integration;

import de.jexcellence.oneblock.api.ItemOwnership;
import de.jexcellence.oneblock.api.OneblockProvider;
import de.jexcellence.oneblock.api.ProfileSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@link IronmanChecks} backed by the JExOneblock {@link OneblockProvider}. Only loaded when JExOneblock is
 * installed. The profile methods answer "no profiles" on an older JExOneblock without them.
 *
 * @author JExcellence
 * @since 2.1.0
 */
final class OneblockIronmanChecks implements IronmanChecks {

    private final OneblockProvider provider;

    private OneblockIronmanChecks(@NotNull OneblockProvider provider) {
        this.provider = provider;
    }

    /** The checks of the registered provider, or {@code null} while JExOneblock has not registered it. */
    static @Nullable IronmanChecks lookup() {
        RegisteredServiceProvider<OneblockProvider> registration =
                Bukkit.getServicesManager().getRegistration(OneblockProvider.class);
        return registration == null ? null : new OneblockIronmanChecks(registration.getProvider());
    }

    @Override
    public boolean isTradeBlocked(@NotNull UUID first, @NotNull UUID second) {
        return provider.isTradeBlocked(first, second);
    }

    @Override
    public boolean sameWorldSet(@NotNull UUID first, @NotNull UUID second) {
        return provider.sameWorldSet(first, second);
    }

    @Override
    public void stampOwner(@NotNull ItemStack item, @NotNull UUID owner) {
        provider.stampItemOwner(item, owner);
    }

    @Override
    public @Nullable String itemRefusal(@NotNull ItemStack item, @NotNull UUID user) {
        ItemOwnership verdict = provider.checkItemUse(item, user);
        return switch (verdict) {
            case IRONMAN_NOT_OWNER -> "ironman-not-owner";
            case IRONMAN_ITEM -> "ironman-item";
            default -> null;
        };
    }

    @Override
    public boolean isSeasonProfile(@NotNull UUID player) {
        try {
            return provider.isSeasonProfile(player);
        } catch (LinkageError ex) {
            return false;
        }
    }

    @Override
    public @Nullable Long activeProfileId(@NotNull UUID player) {
        try {
            return provider.activeProfile(player).map(ProfileSnapshot::id).orElse(null);
        } catch (LinkageError ex) {
            return null;
        }
    }

    @Override
    public @NotNull CompletableFuture<Optional<RewardProfile>> rewardProfile(@NotNull UUID player) {
        try {
            return provider.rewardProfile(player).thenApply(found -> found.map(OneblockIronmanChecks::toRewardProfile));
        } catch (LinkageError ex) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    @Override
    public boolean depositToIslandBank(long islandId, @NotNull String currency, long amount) {
        return provider.bankDeposit(islandId, currency, amount) >= 0L;
    }

    private static @NotNull RewardProfile toRewardProfile(@NotNull ProfileSnapshot snapshot) {
        return new RewardProfile(snapshot.id(), snapshot.slot(), snapshot.label(), snapshot.islandId());
    }
}
