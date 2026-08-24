package de.jexcellence.vote.service;

import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.config.VoteConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class VoteBroadcastService {

    private final VoteConfig config;
    private final AtomicLong lastBroadcastTime = new AtomicLong(0);

    public VoteBroadcastService(@NotNull VoteConfig config) {
        this.config = config;
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
        VoteConfig.BroadcastMode mode = config.getBroadcastMode();
        if (mode == VoteConfig.BroadcastMode.NONE) return;

        // Check cooldown (atomic compare-and-set to avoid race conditions)
        int cooldown = config.getBroadcastCooldownSeconds();
        if (cooldown > 0) {
            long now = System.currentTimeMillis();
            long threshold = now - (cooldown * 1000L);
            // Only proceed if we successfully claim the broadcast slot
            long result = lastBroadcastTime.accumulateAndGet(now,
                    (prev, next) -> prev < threshold ? next : prev);
            if (result != now) {
                return; // silently skip - within cooldown window
            }
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            // In "others" mode, skip the voter
            if (mode == VoteConfig.BroadcastMode.OTHERS
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
        if (!config.isPrivateMessageEnabled()) return;

        r18n().msg("vote.received")
                .with("player", player.getName())
                .with("service", serviceName)
                .with("streak", String.valueOf(streak))
                .send(player);
    }

    /**
     * Notifies the voter that they received the guaranteed reward (granted on
     * every vote, in addition to the weighted pool). Respects the private-message
     * toggle so it stays silent when personal vote messages are disabled.
     */
    public void notifyGuaranteedReward(@NotNull Player player) {
        if (!config.isPrivateMessageEnabled()) return;

        r18n().msg("vote.guaranteed_reward").send(player);
    }

    /**
     * Broadcasts to all online players that a vote party has completed.
     */
    public void broadcastPartyReached(int partyNumber) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            r18n().msg("vote.party.reached")
                    .with("party", String.valueOf(partyNumber))
                    .send(online);
        }
    }

    /**
     * Tells a returning player what was just delivered.
     *
     * <p>Sent after the grants have completed, and phrased in the past tense, because
     * by the time this runs the items are already in their inventory. The itemised
     * lines are the substance - a bare count tells somebody who missed three votes
     * nothing about what they came back to.
     *
     * <p>Identical rewards are folded together with a multiplier, so five votes that
     * each paid coins read as one line rather than five.
     *
     * @param player   the returning player
     * @param votes    how many stored rewards were delivered
     * @param received one description per reward granted, in delivery order
     */
    public void notifyRewardsDelivered(@NotNull Player player,
                                       int votes,
                                       @NotNull List<String> received) {
        if (votes <= 0) return;

        r18n().msg("vote.delivered.header")
                .with("count", String.valueOf(votes))
                .send(player);

        if (received.isEmpty()) {
            // The rewards were commands with nothing describable, or every grant
            // failed. Saying nothing further beats printing an empty list.
            return;
        }

        for (Map.Entry<String, Integer> line : fold(received).entrySet()) {
            var message = r18n().msg(line.getValue() > 1
                    ? "vote.delivered.entry_multiple"
                    : "vote.delivered.entry");
            message.with("reward", line.getKey());
            if (line.getValue() > 1) {
                message.with("times", String.valueOf(line.getValue()));
            }
            message.send(player);
        }
    }

    /**
     * Counts identical descriptions, preserving the order they first appeared in.
     */
    private static @NotNull Map<String, Integer> fold(@NotNull List<String> received) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String entry : received) {
            counts.merge(entry, 1, Integer::sum);
        }
        return counts;
    }

    private static R18nManager r18n() {
        return R18nManager.getInstance();
    }
}
