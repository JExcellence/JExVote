package de.jexcellence.vote.config;

import de.jexcellence.jexplatform.reward.AbstractReward;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * A single purchasable entry in the Vote-Token Shop: a reward bought with vote
 * points. Loaded from the {@code vote-shop} section of {@code rewards.yml}.
 *
 * @param id     unique shop-entry key (used for click routing)
 * @param name   display name shown in the GUI
 * @param icon   GUI icon material
 * @param cost   price in vote points
 * @param reward the reward granted on purchase
 *
 * @author JExcellence
 */
public record VoteShopItem(@NotNull String id,
                           @NotNull String name,
                           @NotNull Material icon,
                           int cost,
                           @NotNull AbstractReward reward) {
}
