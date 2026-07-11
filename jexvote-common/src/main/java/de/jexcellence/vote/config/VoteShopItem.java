package de.jexcellence.vote.config;

import de.jexcellence.jexplatform.reward.AbstractReward;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * A single purchasable entry in the Vote-Token Shop: a reward bought with vote
 * points. Loaded from the {@code vote-shop} section of {@code rewards.yml}.
 *
 * <p>Supports customizable animations and sound effects for purchase feedback.
 *
 * @author JExcellence
 */
public record VoteShopItem(
        @NotNull String id,
        @NotNull String name,
        @NotNull Material icon,
        int cost,
        @NotNull AbstractReward reward,
        @NotNull List<String> description,
        @NotNull ShopEffects effects
) {

    /**
     * Sound and particle effects for shop item purchase.
     */
    public record ShopEffects(
            @NotNull String purchaseSound,
            float purchaseVolume,
            float purchasePitch,
            @NotNull List<String> purchaseMessages
    ) {
        public static final ShopEffects DEFAULTS = new ShopEffects(
                "ENTITY_PLAYER_LEVELUP",
                1.0f,
                1.0f,
                Collections.emptyList()
        );
    }

    /**
     * Creates a VoteShopItem with default effects.
     */
    public static @NotNull VoteShopItem of(
            @NotNull String id,
            @NotNull String name,
            @NotNull Material icon,
            int cost,
            @NotNull AbstractReward reward
    ) {
        return new VoteShopItem(id, name, icon, cost, reward, Collections.emptyList(), ShopEffects.DEFAULTS);
    }

    /**
     * Creates a VoteShopItem with custom effects.
     */
    public static @NotNull VoteShopItem of(
            @NotNull String id,
            @NotNull String name,
            @NotNull Material icon,
            int cost,
            @NotNull AbstractReward reward,
            @NotNull ShopEffects effects
    ) {
        return new VoteShopItem(id, name, icon, cost, reward, Collections.emptyList(), effects);
    }
}
