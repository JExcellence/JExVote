package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Proxy-aware vote sync (V3/V4), DB-authoritative mode.
 *
 * <p>On a network every backend shares one JEHibernate database, so votes, points,
 * streaks and the vote-party counter are already DB-authoritative - every backend
 * reads and writes the same rows. The one thing that diverges is each backend's
 * <b>in-memory</b> live view of the party counter (the progress bar / placeholders):
 * it only refreshes when a vote is processed on that backend, so another backend
 * shows a stale count until its next local vote.
 *
 * <p>This service reconciles that view from the shared DB on a light poll and on
 * player join, so the live party bar is network-wide with <b>zero new
 * infrastructure</b> (the plan's V4 "shared DB = always-on source of truth").
 * An optional Redis pub/sub accelerator - for instant, poll-free updates - is a
 * documented future layer on top of this DB baseline.
 *
 * <p>Enabled only when the edition permits proxy sync (Premium) and
 * {@code proxy.enabled} is set; it is constructed with a live {@link VotePartyService},
 * since the party view is the only network-divergent in-memory state.
 *
 * @author JExcellence
 */
public final class ProxyVoteSyncService implements Listener {

    private final JavaPlugin plugin;
    private final PlatformScheduler scheduler;
    private final Logger logger;
    private final VotePartyService votePartyService;
    private final int pollSeconds;

    public ProxyVoteSyncService(@NotNull JavaPlugin plugin,
                                @NotNull VotePartyService votePartyService,
                                int pollSeconds) {
        this.plugin = plugin;
        this.scheduler = PlatformScheduler.of(plugin);
        this.logger = plugin.getLogger();
        this.votePartyService = votePartyService;
        this.pollSeconds = Math.max(5, pollSeconds);
    }

    /** Starts the DB reconcile poll and registers the join listener. */
    public void start() {
        long periodTicks = (long) pollSeconds * 20L;
        scheduler.runRepeatingAsync(this::reconcile, periodTicks, periodTicks);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        logger.log(Level.INFO, () -> String.format(
                "Proxy vote-sync active - reconciling the network party view from the shared DB every %ds.",
                pollSeconds));
    }

    private void reconcile() {
        try {
            votePartyService.reconcileFromDb();
        } catch (Exception ex) {
            logger.log(Level.WARNING, ex, () -> "Proxy vote-sync DB reconcile failed");
        }
    }

    /** On join, refresh the party view so the joining player sees the network-wide count. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        scheduler.runAsync(this::reconcile);
    }
}
