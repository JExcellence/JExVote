package de.jexcellence.vote.service;

import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.model.VoteSite;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Vote chat messages: the public "X voted" broadcast (mode + anti-spam cooldown), the private thank-you, the
 * guaranteed-reward note, the vote party announcement and the one-message summary of offline votes. Site
 * placeholders show the site's display name instead of its technical service name.
 *
 * @author JExcellence
 */
public class VoteBroadcastService {

    private static final String PARAM_PLAYER = "player";
    private static final String PARAM_SERVICE = "service";
    private static final String PARAM_COUNT = "count";

    private final VoteConfig config;
    private final AtomicLong lastBroadcastTime = new AtomicLong(0);

    public VoteBroadcastService(@NotNull VoteConfig config) {
        this.config = config;
    }

    /**
     * Sends the public broadcast to eligible players, respecting mode and cooldown.
     *
     * @param playerName  the voter's name
     * @param serviceName the vote service name
     * @param voterUuid   the voter's UUID, used for "others" mode filtering (nullable for offline voters)
     */
    public void broadcastVote(@NotNull String playerName, @NotNull String serviceName,
                              @Nullable UUID voterUuid) {
        VoteConfig.BroadcastMode mode = config.getBroadcastMode();
        if (mode == VoteConfig.BroadcastMode.NONE || !claimBroadcastSlot()) {
            return;
        }
        String site = siteName(serviceName);
        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean isVoter = voterUuid != null && online.getUniqueId().equals(voterUuid);
            if (mode != VoteConfig.BroadcastMode.OTHERS || !isVoter) {
                r18n().msg("vote.broadcast").prefix()
                        .with(PARAM_PLAYER, playerName)
                        .with(PARAM_SERVICE, site)
                        .send(online);
            }
        }
    }

    /**
     * Claims the broadcast slot when a cooldown is configured (atomic, so two votes in the same instant
     * cannot both broadcast).
     */
    private boolean claimBroadcastSlot() {
        int cooldown = config.getBroadcastCooldownSeconds();
        if (cooldown <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        long threshold = now - (cooldown * 1000L);
        long result = lastBroadcastTime.accumulateAndGet(now, (prev, next) -> prev < threshold ? next : prev);
        return result == now;
    }

    /**
     * Sends the private thank-you message to the voter.
     *
     * @param player      the voter
     * @param serviceName the vote service name
     * @param streak      the streak after this vote
     */
    public void notifyPlayer(@NotNull Player player, @NotNull String serviceName, int streak) {
        if (!config.isPrivateMessageEnabled()) {
            return;
        }
        r18n().msg("vote.received").prefix()
                .with(PARAM_PLAYER, player.getName())
                .with(PARAM_SERVICE, siteName(serviceName))
                .with("streak", VoteFormat.days(player, streak))
                .send(player);
    }

    /**
     * Tells the voter they received the guaranteed reward (granted on every vote, in addition to the weighted
     * pool). Silent when personal vote messages are disabled.
     *
     * @param player the voter
     */
    public void notifyGuaranteedReward(@NotNull Player player) {
        if (config.isPrivateMessageEnabled()) {
            r18n().msg("vote.guaranteed_reward").prefix().send(player);
        }
    }

    /**
     * Announces a completed vote party to everyone online.
     *
     * @param partyNumber the number of the party that just completed
     */
    public void broadcastPartyReached(int partyNumber) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            r18n().msg("vote.party.reached").prefix()
                    .with("party", String.valueOf(partyNumber))
                    .send(online);
        }
    }

    /**
     * Tells a returning player, in one message, what their offline votes delivered. Sent after the grants
     * completed; identical rewards are folded into one line with a count.
     *
     * @param player   the returning player
     * @param votes    how many stored votes were queued while offline (>= 1)
     * @param received one description per reward actually granted; empty when every grant failed or produced
     *                 no describable reward
     */
    public void notifyRewardsDelivered(@NotNull Player player, int votes, @NotNull List<String> received) {
        if (votes <= 0) {
            return;
        }
        if (received.isEmpty()) {
            r18n().msg("vote.offline-summary-empty").prefix()
                    .with(PARAM_COUNT, String.valueOf(votes))
                    .send(player);
            return;
        }
        r18n().msg("vote.offline-summary").prefix()
                .with(PARAM_COUNT, String.valueOf(votes))
                .with("rewards", buildRewardList(player, received))
                .send(player);
    }

    private @NotNull String siteName(@NotNull String serviceName) {
        for (VoteSite site : config.getVoteSites().values()) {
            if (site.serviceName().equalsIgnoreCase(serviceName)) {
                return site.displayName();
            }
        }
        return serviceName;
    }

    private static @NotNull String buildRewardList(@NotNull Player player, @NotNull List<String> received) {
        Map<String, Integer> folded = new LinkedHashMap<>();
        for (String entry : received) {
            folded.merge(entry, 1, Integer::sum);
        }
        StringBuilder lines = new StringBuilder(folded.size() * 48);
        for (Map.Entry<String, Integer> line : folded.entrySet()) {
            if (!lines.isEmpty()) {
                lines.append("<newline>");
            }
            String key = line.getValue() > 1 ? "vote.offline-summary-entry-stacked" : "vote.offline-summary-entry";
            lines.append(r18n().msg(key)
                    .with("reward", line.getKey())
                    .with(PARAM_COUNT, String.valueOf(line.getValue()))
                    .miniMessage(player));
        }
        return lines.toString();
    }

    private static @NotNull R18nManager r18n() {
        return R18nManager.getInstance();
    }
}
