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

    private static final String BULLET = "·";

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
     * Tells a returning player what was just delivered, as ONE consolidated message.
     *
     * <p>Sent after the grants have completed, phrased past tense, because by the
     * time this runs the items are already in their inventory. The prior version
     * printed a header plus one line per reward - a wall of chat on login. Now the
     * vote count and the reward list are folded into a single i18n key so the
     * returning player sees exactly one card.
     *
     * <p>Identical rewards are collapsed with a multiplier (e.g. "Antike Truhe x2")
     * so five votes that each paid coins read as one line rather than five.
     *
     * @param player   the returning player
     * @param votes    how many stored votes were queued while offline (>= 1)
     * @param received one description per reward actually granted; empty when
     *                 every grant failed or produced no describable reward
     */
    public void notifyRewardsDelivered(@NotNull Player player,
                                       int votes,
                                       @NotNull List<String> received) {
        if (votes <= 0) return;

        if (received.isEmpty()) {
            // The rewards were commands with nothing describable, or every grant
            // failed. Send a single fallback line so the player still sees that
            // their offline votes were processed.
            r18n().msg("vote.offline-summary-empty")
                    .with("count", String.valueOf(votes))
                    .send(player);
            return;
        }

        r18n().msg("vote.offline-summary")
                .with("count", String.valueOf(votes))
                .with("rewards", buildRewardList(received))
                .send(player);
    }

    /**
     * Builds the bullet-per-line reward block. Each entry is prefixed with
     * " {@literal ·} " and lines are separated by newlines, so the final chat
     * card reads as one bulleted list under the summary header.
     */
    private static @NotNull String buildRewardList(@NotNull List<String> received) {
        Map<String, Integer> folded = fold(received);
        StringBuilder sb = new StringBuilder(folded.size() * 32);
        boolean first = true;
        for (Map.Entry<String, Integer> line : folded.entrySet()) {
            if (!first) {
                sb.append("<newline>");
            }
            first = false;
            sb.append("<gray> </gray>").append(BULLET).append(' ').append(line.getKey());
            if (line.getValue() > 1) {
                sb.append(" x").append(line.getValue());
            }
        }
        return sb.toString();
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
