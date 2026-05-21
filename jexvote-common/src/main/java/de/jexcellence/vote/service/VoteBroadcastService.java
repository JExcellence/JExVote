package de.jexcellence.vote.service;

import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.config.VoteConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class VoteBroadcastService {

    private final VoteConfig.BroadcastMode broadcastMode;
    private final int broadcastCooldownSeconds;
    private final boolean privateMessageEnabled;
    private final AtomicLong lastBroadcastTime = new AtomicLong(0);

    public VoteBroadcastService(@NotNull VoteConfig.BroadcastMode broadcastMode,
                                int broadcastCooldownSeconds,
                                boolean privateMessageEnabled) {
        this.broadcastMode = broadcastMode;
        this.broadcastCooldownSeconds = broadcastCooldownSeconds;
        this.privateMessageEnabled = privateMessageEnabled;
    }

    /**
     * Sends the public broadcast to eligible players, respecting mode and cooldown.
     *
     * @param playerName the voter's name
     * @param serviceName the vote service name
     * @param voterUuid the voter's UUID, used for "others" mode filtering (nullable for offline voters)
     */
    public void broadcastVote(@NotNull String playerName, @NotNull String serviceName,
                              @Nullable UUID voterUuid) {
        if (broadcastMode == VoteConfig.BroadcastMode.NONE) return;

        // Check cooldown (atomic compare-and-set to avoid race conditions)
        if (broadcastCooldownSeconds > 0) {
            long now = System.currentTimeMillis();
            long threshold = now - (broadcastCooldownSeconds * 1000L);
            // Only proceed if we successfully claim the broadcast slot
            long result = lastBroadcastTime.accumulateAndGet(now,
                    (prev, next) -> prev < threshold ? next : prev);
            if (result != now) {
                return; // silently skip — within cooldown window
            }
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            // In "others" mode, skip the voter
            if (broadcastMode == VoteConfig.BroadcastMode.OTHERS
                    && voterUuid != null
                    && online.getUniqueId().equals(voterUuid)) {
                continue;
            }

            r18n().msg("vote.broadcast")
                    .with("player", playerName)
                    .with("service", serviceName)
                    .send(online);
        }
    }

    /**
     * Sends the private "thank you" message to the voter.
     */
    public void notifyPlayer(@NotNull Player player, @NotNull String serviceName, int streak) {
        if (!privateMessageEnabled) return;

        r18n().msg("vote.received")
                .with("player", player.getName())
                .with("service", serviceName)
                .with("streak", String.valueOf(streak))
                .send(player);
    }

    /**
     * Notifies a player about pending rewards delivered on login.
     */
    public void notifyPendingRewards(@NotNull Player player, int count) {
        if (count <= 0) return;
        r18n().msg("vote.pending_rewards")
                .with("count", String.valueOf(count))
                .send(player);
    }

    private static R18nManager r18n() {
        return R18nManager.getInstance();
    }
}
