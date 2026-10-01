package de.jexcellence.vote.service;

import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Read side of the suite-wide "share my rewards publicly" setting. JExEssentials writes it with
 * {@code /rewardshare} into the player's persistent data container under {@code jexsuite:share_rewards}
 * (byte 1 = shared, 0 = private, missing = shared), so no plugin dependency is needed.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public final class RewardSharePreference {

    private static final NamespacedKey KEY = new NamespacedKey("jexsuite", "share_rewards");
    private static final byte PRIVATE = 0;

    private RewardSharePreference() {
    }

    /**
     * Whether an online player's rewards may be announced in public chat. Call on the thread that owns the player.
     *
     * @param player the player who got the reward
     * @return {@code true} unless the player turned sharing off
     */
    public static boolean isShared(@NotNull Player player) {
        return isShared(player.getPersistentDataContainer());
    }

    /**
     * Whether a player's rewards may be announced in public chat, online or offline. An offline player's data is
     * read from disk, so call this off the server thread.
     *
     * @param uuid the player who got the reward
     * @return {@code true} unless the player turned sharing off
     */
    public static boolean isShared(@NotNull UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return isShared(online);
        }
        return isShared(Bukkit.getOfflinePlayer(uuid).getPersistentDataContainer());
    }

    private static boolean isShared(@NotNull PersistentDataContainerView container) {
        Byte stored = container.get(KEY, PersistentDataType.BYTE);
        return stored == null || stored != PRIVATE;
    }
}
