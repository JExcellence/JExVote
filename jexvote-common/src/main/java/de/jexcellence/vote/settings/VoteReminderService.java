package de.jexcellence.vote.settings;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.scheduler.TaskHandle;
import de.jexcellence.jextranslate.MessageBuilder;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.database.entity.VotePlayerSettingsEntity;
import de.jexcellence.vote.database.repository.VotePlayerSettingsRepository;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.vote.service.VoteService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends the reminders players turn on in {@code /vote settings}:
 * <ul>
 *   <li>a chat line or title when at least one vote site is ready again, at most once per
 *       {@code reminders.interval-minutes};</li>
 *   <li>a streak warning once per vote when the streak breaks within {@code reminders.streak-warning-hours}
 *       and a site can be voted on;</li>
 *   <li>a Discord direct message (JExDiscord, linked account) while the player is offline, at most once per
 *       {@code reminders.discord-interval-minutes}.</li>
 * </ul>
 * The check runs off the main thread every {@code reminders.check-seconds}. Each player remembers when the next
 * database look-up is worth doing (the next site cooldown end, the start of the streak warning window), so an
 * idle player costs no queries.
 *
 * @author JExcellence
 * @since 3.4.0
 */
public final class VoteReminderService {

    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60L * MINUTE_MS;
    private static final long STREAK_RECHECK_MS = 30L * MINUTE_MS;
    private static final long DISCORD_SWEEP_MS = 10L * MINUTE_MS;
    private static final int DISCORD_COLOR = 0xA78BFA;
    private static final String KEY = "vote.reminder.";
    private static final String KEY_CHAT = KEY + "chat-v2";
    private static final String KEY_CHAT_HINT = KEY + "chat-hint-v2";
    private static final String PARAM_READY = "ready";
    private static final String PARAM_TOTAL = "total";

    private final PlatformScheduler scheduler;
    private final VoteService voteService;
    private final VoteConfig config;
    private final VoteSettingsService settings;
    private final VotePlayerSettingsRepository repository;
    private final Logger logger;
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDiscordAt = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastDiscordSweep = new AtomicLong(0L);
    private final AtomicReference<TaskHandle> task = new AtomicReference<>();

    /** When the next look-ups for one player are worth doing, and what was already sent. */
    private static final class PlayerState {
        private volatile long lastReminderAt;
        private volatile long nextSiteCheckAt;
        private volatile long nextStreakCheckAt;
        private volatile long warnedForVoteAt = -1L;
    }

    /** What one look-up found for one player. */
    private record Lookup(@NotNull Map<String, Long> cooldowns, @Nullable VoteSnapshot stats) {

        long readyCount() {
            return cooldowns.values().stream().filter(seconds -> seconds <= 0L).count();
        }

        long secondsUntilFirstReady() {
            return cooldowns.values().stream().mapToLong(Long::longValue).filter(seconds -> seconds > 0L)
                    .min().orElse(0L);
        }
    }

    public VoteReminderService(@NotNull JavaPlugin plugin,
                               @NotNull VoteService voteService,
                               @NotNull VoteConfig config,
                               @NotNull VoteSettingsService settings,
                               @NotNull VotePlayerSettingsRepository repository) {
        this.scheduler = PlatformScheduler.of(plugin);
        this.voteService = voteService;
        this.config = config;
        this.settings = settings;
        this.repository = repository;
        this.logger = plugin.getLogger();
    }

    /** Starts the periodic check; the period is read from the config at this moment. */
    public void start() {
        long period = 20L * config.getReminderSettings().checkSeconds();
        TaskHandle previous = task.getAndSet(scheduler.runRepeatingAsync(this::tick, period, period));
        if (previous != null) {
            previous.cancel();
        }
    }

    /** Stops the periodic check. */
    public void stop() {
        TaskHandle running = task.getAndSet(null);
        if (running != null) {
            running.cancel();
        }
    }

    /**
     * Drops what the reminder check remembers about a player (reminder and warning timestamps).
     *
     * @param player the player's UUID
     */
    public void forget(@NotNull UUID player) {
        states.remove(player);
        lastDiscordAt.remove(player);
    }

    private void tick() {
        VoteConfig.ReminderSettings timing = config.getReminderSettings();
        if (!timing.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player online : Bukkit.getOnlinePlayers()) {
            checkOnline(online, timing, now);
        }
        if (now - lastDiscordSweep.get() >= DISCORD_SWEEP_MS) {
            lastDiscordSweep.set(now);
            sweepDiscord(timing, now);
        }
    }

    // ── In-game reminders ────────────────────────────────────────────────

    private void checkOnline(@NotNull Player player, @NotNull VoteConfig.ReminderSettings timing, long now) {
        UUID uuid = player.getUniqueId();
        VoteSettings chosen = settings.get(uuid);
        PlayerState state = states.computeIfAbsent(uuid, ignored -> new PlayerState());
        boolean reminderDue = wantsSiteReminder(uuid, chosen)
                && now - state.lastReminderAt >= timing.intervalMinutes() * MINUTE_MS
                && now >= state.nextSiteCheckAt;
        boolean streakDue = wantsStreakWarning(uuid, chosen) && now >= state.nextStreakCheckAt;
        if ((!reminderDue && !streakDue) || !inFlight.add(uuid)) {
            return;
        }
        CompletableFuture<VoteSnapshot> stats = streakDue
                ? voteService.getPlayerStats(uuid) : CompletableFuture.completedFuture(null);
        voteService.voteCooldownsSeconds(uuid)
                .thenCombine(stats, Lookup::new)
                .thenAccept(lookup -> {
                    if (reminderDue) {
                        handleSiteReminder(player, chosen.reminder(), state, lookup, now);
                    }
                    if (streakDue) {
                        handleStreakWarning(player, state, lookup, timing, now);
                    }
                })
                .whenComplete((ignored, error) -> {
                    inFlight.remove(uuid);
                    if (error != null) {
                        logger.log(Level.WARNING, error, () -> "Vote reminder check failed for " + player.getName());
                    }
                });
    }

    private boolean wantsSiteReminder(@NotNull UUID uuid, @NotNull VoteSettings chosen) {
        return chosen.reminder() != ReminderMode.DISABLED
                && settings.availability(uuid, VoteSettingOption.REMINDER) == VoteSettingsService.Availability.AVAILABLE;
    }

    private boolean wantsStreakWarning(@NotNull UUID uuid, @NotNull VoteSettings chosen) {
        return chosen.streakWarning()
                && settings.availability(uuid, VoteSettingOption.STREAK_WARNING)
                == VoteSettingsService.Availability.AVAILABLE;
    }

    private void handleSiteReminder(@NotNull Player player, @NotNull ReminderMode mode, @NotNull PlayerState state,
                                    @NotNull Lookup lookup, long now) {
        long ready = lookup.readyCount();
        if (ready <= 0L) {
            state.nextSiteCheckAt = now + lookup.secondsUntilFirstReady() * 1000L;
            return;
        }
        state.lastReminderAt = now;
        int total = lookup.cooldowns().size();
        scheduler.runAtEntity(player, () -> sendSiteReminder(player, mode, ready, total));
    }

    private static void sendSiteReminder(@NotNull Player player, @NotNull ReminderMode mode, long ready, int total) {
        if (!player.isOnline()) {
            return;
        }
        MessageBuilder message = msg(mode == ReminderMode.TITLE ? KEY + "title" : KEY_CHAT)
                .with(PARAM_READY, VoteFormat.number(player, ready))
                .with(PARAM_TOTAL, VoteFormat.number(player, total));
        if (mode == ReminderMode.TITLE) {
            message.showTitle(player, KEY + "subtitle");
        } else {
            message.prefix().send(player);
            msg(KEY_CHAT_HINT).send(player);
        }
    }

    private void handleStreakWarning(@NotNull Player player, @NotNull PlayerState state, @NotNull Lookup lookup,
                                     @NotNull VoteConfig.ReminderSettings timing, long now) {
        VoteSnapshot stats = lookup.stats();
        Instant lastVote = stats == null ? null : stats.lastVoteAt();
        if (stats == null || lastVote == null || stats.currentStreak() <= 0) {
            state.nextStreakCheckAt = now + STREAK_RECHECK_MS;
            return;
        }
        long lastVoteAt = lastVote.toEpochMilli();
        long deadline = lastVoteAt + config.getStreakTimeoutHours() * HOUR_MS;
        long windowStart = deadline - timing.streakWarningHours() * HOUR_MS;
        if (now < windowStart) {
            state.nextStreakCheckAt = Math.min(windowStart, now + STREAK_RECHECK_MS);
        } else if (now >= deadline || state.warnedForVoteAt == lastVoteAt) {
            state.nextStreakCheckAt = now + STREAK_RECHECK_MS;
        } else if (lookup.readyCount() <= 0L) {
            state.nextStreakCheckAt = now + Math.max(MINUTE_MS, lookup.secondsUntilFirstReady() * 1000L);
        } else {
            state.warnedForVoteAt = lastVoteAt;
            state.nextStreakCheckAt = deadline;
            long secondsLeft = Duration.ofMillis(deadline - now).toSeconds();
            int streak = stats.currentStreak();
            scheduler.runAtEntity(player, () -> sendStreakWarning(player, streak, secondsLeft));
        }
    }

    private static void sendStreakWarning(@NotNull Player player, int streak, long secondsLeft) {
        if (!player.isOnline()) {
            return;
        }
        msg(KEY + "streak-warning").prefix()
                .with("streak", VoteFormat.days(player, streak))
                .with("time", VoteFormat.duration(player, secondsLeft))
                .send(player);
    }

    // ── Discord reminders ────────────────────────────────────────────────

    private void sweepDiscord(@NotNull VoteConfig.ReminderSettings timing, long now) {
        DiscordReminderBridge discord = settings.discord();
        if (!discord.isInstalled()) {
            return;
        }
        long interval = timing.discordIntervalMinutes() * MINUTE_MS;
        repository.findDiscordReminderAsync().thenAccept(rows -> {
            for (VotePlayerSettingsEntity row : rows) {
                UUID uuid = row.getPlayerUuid();
                boolean due = now - lastDiscordAt.getOrDefault(uuid, 0L) >= interval;
                if (due && row.toSettings().discordReminder() && Bukkit.getPlayer(uuid) == null
                        && discord.isLinked(uuid)) {
                    remindOnDiscord(discord, uuid, now);
                }
            }
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, ex, () -> "Discord vote reminder sweep failed");
            return null;
        });
    }

    private void remindOnDiscord(@NotNull DiscordReminderBridge discord, @NotNull UUID uuid, long now) {
        voteService.voteCooldownsSeconds(uuid).thenAccept(cooldowns -> {
            List<String> lines = new ArrayList<>();
            for (VoteSite site : voteService.getVoteSites().values()) {
                if (cooldowns.getOrDefault(site.serviceName(), 0L) <= 0L) {
                    lines.add(siteLine(site));
                }
            }
            if (lines.isEmpty()) {
                return;
            }
            lastDiscordAt.put(uuid, now);
            String description = msg(KEY + "discord.description")
                    .with(PARAM_READY, lines.size())
                    .with(PARAM_TOTAL, cooldowns.size())
                    .with("sites", String.join("\n", lines))
                    .toPlainString(null);
            discord.sendDirectMessage(uuid, msg(KEY + "discord.title").toPlainString(null), description, DISCORD_COLOR);
        });
    }

    private static @NotNull String siteLine(@NotNull VoteSite site) {
        String name = MiniMessage.miniMessage().stripTags(site.displayName());
        String key = site.voteUrl() == null ? KEY + "discord.site" : KEY + "discord.site-link";
        return msg(key).with("site", name).with("url", site.voteUrl() == null ? "" : site.voteUrl())
                .toPlainString(null);
    }

    private static @NotNull MessageBuilder msg(@NotNull String key) {
        return R18nManager.getInstance().msg(key);
    }
}
