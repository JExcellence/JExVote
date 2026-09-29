package de.jexcellence.vote.settings;

import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.config.VoteFeatures;
import de.jexcellence.vote.database.entity.VotePlayerSettingsEntity;
import de.jexcellence.vote.database.repository.VotePlayerSettingsRepository;
import de.jexcellence.vote.service.VotePreferences;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds the vote settings of the online players in memory and writes changes to the
 * {@code jexvote_player_settings} table. Settings are loaded on join and dropped on quit; a player whose row is
 * not loaded yet (or who has none) gets {@link VoteSettings#DEFAULTS}. Writes for one player run one after the
 * other, so quick clicks cannot insert the same row twice.
 *
 * <p>Also decides which options a player can use right now: an option whose server feature is off is shown as
 * unavailable, the Discord option only appears with JExDiscord installed and the account linked.
 *
 * @author JExcellence
 * @since 3.4.0
 */
public final class VoteSettingsService implements VotePreferences, Listener {

    /** Whether an option can be changed right now. */
    public enum Availability {
        /** Shown and changeable. */
        AVAILABLE,
        /** Shown, but the server has the feature off. */
        UNAVAILABLE,
        /** Not shown at all. */
        HIDDEN
    }

    private final VotePlayerSettingsRepository repository;
    private final VoteConfig config;
    private final VoteFeatures features;
    private final DiscordReminderBridge discord;
    private final Logger logger;
    private final Map<UUID, VoteSettings> loaded = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> writes = new ConcurrentHashMap<>();

    public VoteSettingsService(@NotNull VotePlayerSettingsRepository repository,
                               @NotNull VoteConfig config,
                               @NotNull VoteFeatures features,
                               @NotNull DiscordReminderBridge discord,
                               @NotNull Logger logger) {
        this.repository = repository;
        this.config = config;
        this.features = features;
        this.discord = discord;
        this.logger = logger;
    }

    /** Loads the settings of everyone already online (plugin enabled while players are on). */
    public void loadOnlinePlayers() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            load(online.getUniqueId());
        }
    }

    /**
     * Loads a player's settings into memory.
     *
     * @param player the player's UUID
     * @return completes with the loaded settings (defaults when the player has no row)
     */
    public @NotNull CompletableFuture<VoteSettings> load(@NotNull UUID player) {
        CompletableFuture<Void> pending = writes.getOrDefault(player, CompletableFuture.completedFuture(null));
        return pending.thenCompose(ignored -> repository.findByUuidAsync(player))
                .thenApply(row -> row.map(VotePlayerSettingsEntity::toSettings).orElse(VoteSettings.DEFAULTS))
                .thenApply(settings -> {
                    loaded.putIfAbsent(player, settings);
                    return loaded.get(player);
                })
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, ex, () -> "Could not load vote settings of " + player);
                    return get(player);
                });
    }

    /**
     * @param player the player's UUID
     * @return the player's settings, or the defaults while they are not loaded
     */
    public @NotNull VoteSettings get(@NotNull UUID player) {
        return loaded.getOrDefault(player, VoteSettings.DEFAULTS);
    }

    /**
     * Changes one option and saves it.
     *
     * @param player  the player's UUID
     * @param option  the option to change
     * @param forward {@code true} for the next value, {@code false} for the previous one
     * @return the new settings
     */
    public @NotNull VoteSettings cycle(@NotNull UUID player, @NotNull VoteSettingOption option, boolean forward) {
        return update(player, option.cycle(get(player), forward));
    }

    /**
     * Replaces the player's settings and saves them.
     *
     * @param player   the player's UUID
     * @param settings the new settings
     * @return {@code settings}
     */
    public @NotNull VoteSettings update(@NotNull UUID player, @NotNull VoteSettings settings) {
        loaded.put(player, settings);
        writes.compute(player, (uuid, previous) -> {
            CompletableFuture<Void> before = previous == null ? CompletableFuture.completedFuture(null) : previous;
            return before.thenCompose(ignored -> persist(uuid, settings));
        });
        return settings;
    }

    private @NotNull CompletableFuture<Void> persist(@NotNull UUID player, @NotNull VoteSettings settings) {
        return repository.findByUuidAsync(player).thenAccept(row -> {
            VotePlayerSettingsEntity entity = row.orElseGet(() -> new VotePlayerSettingsEntity(player));
            entity.apply(settings);
            if (row.isPresent()) {
                repository.update(entity);
            } else {
                repository.create(entity);
            }
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, ex, () -> "Could not save vote settings of " + player);
            return null;
        });
    }

    /**
     * Deletes the player's settings row and drops the cached settings, after any pending write has finished.
     *
     * @param player the player's UUID
     * @return completes with {@code true} when a row was deleted
     */
    public @NotNull CompletableFuture<Boolean> delete(@NotNull UUID player) {
        loaded.remove(player);
        CompletableFuture<Void> pending = writes.getOrDefault(player, CompletableFuture.completedFuture(null));
        CompletableFuture<Boolean> deleted = pending
                .thenCompose(ignored -> repository.findByUuidAsync(player))
                .thenApply(row -> {
                    row.ifPresent(repository::deleteEntity);
                    return row.isPresent();
                });
        writes.remove(player);
        return deleted.exceptionally(ex -> {
            logger.log(Level.WARNING, ex, () -> "Could not delete vote settings of " + player);
            return false;
        });
    }

    /**
     * Whether {@code player} can use {@code option} right now.
     *
     * @param player the player's UUID
     * @param option the option
     * @return the availability
     */
    public @NotNull Availability availability(@NotNull UUID player, @NotNull VoteSettingOption option) {
        boolean reminders = config.getReminderSettings().enabled();
        return switch (option) {
            case REMINDER -> available(reminders);
            case STREAK_WARNING -> available(reminders && features.streaks());
            case BROADCASTS -> available(config.getBroadcastMode() != VoteConfig.BroadcastMode.NONE);
            case PARTY -> available(features.party());
            case EFFECTS -> available(config.isFeatureEffects());
            case DISCORD -> discordAvailability(player, reminders);
            default -> Availability.HIDDEN;
        };
    }

    private @NotNull Availability discordAvailability(@NotNull UUID player, boolean reminders) {
        if (!discord.isInstalled() || !discord.isLinked(player)) {
            return Availability.HIDDEN;
        }
        return available(reminders);
    }

    private static @NotNull Availability available(boolean serverAllows) {
        return serverAllows ? Availability.AVAILABLE : Availability.UNAVAILABLE;
    }

    /** @return the Discord bridge, for the reminder service. */
    public @NotNull DiscordReminderBridge discord() {
        return discord;
    }

    @Override
    public boolean showsBroadcasts(@NotNull UUID viewer) {
        return get(viewer).broadcasts();
    }

    @Override
    public boolean showsPartyAnnouncements(@NotNull UUID viewer) {
        return get(viewer).partyAnnouncements();
    }

    @Override
    public boolean playsEffects(@NotNull UUID player) {
        return get(player).effects();
    }

    /**
     * Loads the joining player's settings.
     *
     * @param event the join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        load(event.getPlayer().getUniqueId());
    }

    /**
     * Drops the leaving player's settings from memory once their pending writes are done.
     *
     * @param event the quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        loaded.remove(player);
        CompletableFuture<Void> pending = writes.get(player);
        if (pending != null) {
            pending.whenComplete((ignored, error) -> writes.remove(player, pending));
        }
    }
}
