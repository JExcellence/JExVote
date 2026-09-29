package de.jexcellence.vote.integration;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The Ironman isolation checks {@link IronmanGate} delegates to, free of JExOneblock types.
 *
 * @author JExcellence
 * @since 2.1.0
 */
interface IronmanChecks {

    boolean isTradeBlocked(@NotNull UUID first, @NotNull UUID second);

    boolean sameWorldSet(@NotNull UUID first, @NotNull UUID second);

    void stampOwner(@NotNull ItemStack item, @NotNull UUID owner);

    @Nullable String itemRefusal(@NotNull ItemStack item, @NotNull UUID user);
}
