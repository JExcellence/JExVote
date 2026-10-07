package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.gui.chat.ChatPanel;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.config.VoteEffectsConfig;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.gui.style.VoteRarityStyle;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.vote.view.VoteRewardDescriber;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Vote chat messages: the public "X voted" broadcast (mode + anti-spam cooldown), the public lucky win card, the
 * private thank-you, the guaranteed-reward note, the vote party announcement and the one-message summary of
 * offline votes. Site
 * placeholders show the site's display name instead of its technical service name.
 *
 * @author JExcellence
 */
public class VoteBroadcastService {

    private static final String PARAM_PLAYER = "player";
    private static final String PARAM_SERVICE = "service";
    private static final String PARAM_COUNT = "count";
    private static final String PANEL_KEY = "vote.chat-panel-v1.delivered.";

    private final VoteConfig config;
    private final AtomicLong lastBroadcastTime = new AtomicLong(0);
    private final AtomicReference<VoteEffectsConfig> effects = new AtomicReference<>();
    private final AtomicReference<VotePreferences> preferences = new AtomicReference<>(VotePreferences.EVERYTHING);

    public VoteBroadcastService(@NotNull VoteConfig config) {
        this.config = config;
    }

    /**
     * Sets the {@code vote-effects} settings used by {@link #playVoteEffects}.
     *
     * @param effectsConfig the loaded effects config
     */
    public void setEffects(@NotNull VoteEffectsConfig effectsConfig) {
        effects.set(effectsConfig);
    }

    /**
     * Sets the per-player preferences that filter broadcasts, party announcements and effects.
     *
     * @param playerPreferences the preferences source
     */
    public void setPreferences(@NotNull VotePreferences playerPreferences) {
        preferences.set(playerPreferences);
    }

    /**
     * Plays the configured vote sound for the voter: the milestone sound and title when the vote landed on a
     * streak milestone, the streak sound when the streak grew past day one, the vote sound otherwise. Silent when
     * {@code features.effects} is off or the voter turned effects off in their settings. Runs on the voter's
     * thread.
     *
     * @param player    the voter
     * @param streak    the streak after this vote
     * @param milestone whether this streak day is a configured milestone
     */
    public void playVoteEffects(@NotNull Player player, int streak, boolean milestone) {
        VoteEffectsConfig loaded = effects.get();
        if (loaded == null || !config.isFeatureEffects() || !preferences.get().playsEffects(player.getUniqueId())) {
            return;
        }
        VoteEffectsConfig.VoteEffects settings = loaded.getEffects();
        boolean streaks = config.isFeatureStreaks();
        if (streaks && milestone) {
            playSound(player, settings.milestoneSound(), settings.milestoneVolume(), settings.milestonePitch());
            MiniMessage mini = MiniMessage.miniMessage();
            player.showTitle(Title.title(mini.deserialize(settings.milestoneTitle()),
                    mini.deserialize(settings.milestoneSubtitle()),
                    Title.Times.times(settings.titleFadeIn(), settings.titleStay(), settings.titleFadeOut())));
        } else if (streaks && streak > 1) {
            playSound(player, settings.streakSound(), settings.streakVolume(), settings.streakPitch());
        } else {
            playSound(player, settings.voteSound(), settings.voteVolume(), settings.votePitch());
        }
    }

    private static void playSound(@NotNull Player player, @NotNull String soundName, float volume, float pitch) {
        try {
            player.playSound(player, Sound.valueOf(soundName), volume, pitch);
        } catch (IllegalArgumentException ex) {
            player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, volume, pitch);
        }
    }

    /**
     * Sends the public broadcast to eligible players, respecting mode, cooldown and each viewer's settings. The
     * voter's own copy follows the server mode only; nobody else sees it when the voter turned reward sharing off.
     *
     * @param playerName  the voter's name
     * @param serviceName the vote service name
     * @param voterUuid   the voter's UUID, used for "others" mode filtering (nullable for offline voters)
     * @param voterShares whether the voter shares rewards publicly
     */
    public void broadcastVote(@NotNull String playerName, @NotNull String serviceName,
                              @Nullable UUID voterUuid, boolean voterShares) {
        VoteConfig.BroadcastMode mode = config.getBroadcastMode();
        if (mode == VoteConfig.BroadcastMode.NONE || !claimBroadcastSlot()) {
            return;
        }
        String site = siteName(serviceName);
        VotePreferences viewers = preferences.get();
        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean isVoter = voterUuid != null && online.getUniqueId().equals(voterUuid);
            boolean wanted = isVoter ? mode != VoteConfig.BroadcastMode.OTHERS
                    : voterShares && viewers.showsBroadcasts(online.getUniqueId());
            if (wanted) {
                r18n().msg("vote.broadcast-v2")
                        .with(PARAM_PLAYER, playerName)
                        .with(PARAM_SERVICE, site)
                        .send(online);
            }
        }
    }

    /**
     * Posts the public two-line card of an announced lucky or chance prize to every other player who did not turn
     * vote broadcasts off. Silent when broadcasts are off on the server or the winner does not share rewards; the
     * winner's private message is sent by the reward itself.
     *
     * @param winner  the player who won
     * @param reward  the prize
     * @param percent the drop chance, scaled to 0-100
     * @param shared  whether the winner shares rewards publicly
     */
    public void broadcastLuckyWin(@NotNull Player winner, @NotNull AbstractReward reward, double percent,
                                  boolean shared) {
        if (!shared || config.getBroadcastMode() == VoteConfig.BroadcastMode.NONE) {
            return;
        }
        VoteRarityStyle rarity = VoteRarityStyle.byPercent(percent);
        String accentKey = "vote.reward-card.accent." + rarity.name().toLowerCase(Locale.ROOT);
        VotePreferences viewers = preferences.get();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(winner.getUniqueId()) && viewers.showsBroadcasts(online.getUniqueId())) {
                r18n().msg("vote.reward-card.lucky")
                        .with("accent", r18n().msg(accentKey).miniMessage(online))
                        .with(PARAM_PLAYER, winner.getName())
                        .with("reward", VoteRewardDescriber.describe(reward, online))
                        .with("rarity", r18n().msg(rarity.key()).miniMessage(online))
                        .with("chance", VoteFormat.percent(online, percent))
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
     * @param player  the voter
     * @param rewards the guaranteed rewards, named in the message
     */
    public void notifyGuaranteedReward(@NotNull Player player, @NotNull List<AbstractReward> rewards) {
        if (!config.isPrivateMessageEnabled() || rewards.isEmpty()) {
            return;
        }
        String names = rewards.stream()
                .map(reward -> VoteRewardDescriber.describe(reward, player))
                .collect(Collectors.joining(r18n().msg("vote.list-separator").miniMessage(player)));
        r18n().msg("vote.guaranteed-rewards").with("rewards", names).prefix().send(player);
    }

    /**
     * Announces a completed vote party to everyone online who did not turn party announcements off.
     *
     * @param partyNumber the number of the party that just completed
     */
    public void broadcastPartyReached(int partyNumber) {
        VotePreferences viewers = preferences.get();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!viewers.showsPartyAnnouncements(online.getUniqueId())) {
                continue;
            }
            r18n().msg("vote.party.reached-v2")
                    .with("party", String.valueOf(partyNumber))
                    .send(online);
        }
    }

    /**
     * Tells a player, as one chat panel, what their pending votes delivered: the votes cast while offline, or the
     * rewards kept from the JExOneblock Season profile for this Normal profile. Sent after the grants completed;
     * identical rewards are folded into one line with a count.
     *
     * @param player            the recipient
     * @param votes             how many stored entries were delivered (>= 1)
     * @param received          one description per reward actually granted; empty when every grant failed or
     *                          produced no describable reward
     * @param fromSeasonProfile whether the entries were kept from the Season profile
     */
    public void notifyRewardsDelivered(@NotNull Player player, int votes, @NotNull List<String> received,
                                       boolean fromSeasonProfile) {
        if (votes <= 0) {
            return;
        }
        if (received.isEmpty()) {
            String emptyKey = fromSeasonProfile ? "vote.normal-profile.delivered-empty" : "vote.offline-summary-empty";
            r18n().msg(emptyKey).prefix()
                    .with(PARAM_COUNT, String.valueOf(votes))
                    .send(player);
            return;
        }
        String source = fromSeasonProfile ? "season-" : "offline-";
        ChatPanel panel = ChatPanel.create()
                .header(r18n().msg(PANEL_KEY + source + "header").toComponent(player))
                .context(r18n().msg(PANEL_KEY + source + "context")
                        .with(PARAM_COUNT, String.valueOf(votes)).toComponent(player))
                .gap();
        rewardLines(player, received).forEach(panel::line);
        panel.footer(r18n().msg(PANEL_KEY + "footer").toComponent(player)).send(player);
    }

    private @NotNull String siteName(@NotNull String serviceName) {
        for (VoteSite site : config.getVoteSites().values()) {
            if (site.serviceName().equalsIgnoreCase(serviceName)) {
                return site.displayName();
            }
        }
        return serviceName;
    }

    private static @NotNull List<Component> rewardLines(@NotNull Player player, @NotNull List<String> received) {
        Map<String, Integer> folded = new LinkedHashMap<>();
        for (String entry : received) {
            folded.merge(entry, 1, Integer::sum);
        }
        List<Component> lines = new ArrayList<>(folded.size());
        for (Map.Entry<String, Integer> line : folded.entrySet()) {
            String key = line.getValue() > 1 ? "entry-stacked" : "entry";
            lines.add(r18n().msg(PANEL_KEY + key)
                    .with("reward", line.getKey())
                    .with(PARAM_COUNT, String.valueOf(line.getValue()))
                    .toComponent(player));
        }
        return lines;
    }

    private static @NotNull R18nManager r18n() {
        return R18nManager.getInstance();
    }
}
