package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.database.entity.VoteSyncEventEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Proxy-aware vote sync (V3/V4) - orchestrates the two layers of network sync, both
 * built on the shared database with no third-party dependency:
 *
 * <ol>
 *   <li><b>Fast layer</b> - the {@link OutboxProxyEventBus}: backends publish
 *       party-progress / party-complete events to a DB outbox and poll it (~2s), so
 *       the live party bar and the "party reached" broadcast are near-instant
 *       network-wide.</li>
 *   <li><b>Safety net</b> - a slower periodic reconcile (plus a reconcile on player
 *       join) that re-reads the authoritative party row from the DB, so the bar is
 *       correct even if an outbox event was missed or purged before a backend polled.</li>
 * </ol>
 *
 * <p>Everything is DB-authoritative: votes, points, streaks and the party counter
 * already live in shared rows. This service only makes each backend's in-memory
 * <b>view</b> of that shared state converge quickly.
 *
 * @author JExcellence
 */
public final class ProxyVoteSyncService implements Listener {

    private final JavaPlugin plugin;
    private final PlatformScheduler scheduler;
    private final Logger logger;
    private final VotePartyService votePartyService;
    private final VoteBroadcastService broadcastService;
    private final OutboxProxyEventBus bus;
    private final int reconcileSeconds;

    public ProxyVoteSyncService(@NotNull JavaPlugin plugin,
                                @NotNull VotePartyService votePartyService,
                                @NotNull VoteBroadcastService broadcastService,
                                @NotNull OutboxProxyEventBus bus,
                                int reconcileSeconds) {
        this.plugin = plugin;
        this.scheduler = PlatformScheduler.of(plugin);
        this.logger = plugin.getLogger();
        this.votePartyService = votePartyService;
        this.broadcastService = broadcastService;
        this.bus = bus;
        this.reconcileSeconds = Math.max(10, reconcileSeconds);
    }

    /** Starts the outbox fast-layer, the reconcile safety net, and the join listener. */
    public void start() {
        bus.start(this::applyForeign);
        long reconcileTicks = (long) reconcileSeconds * 20L;
        scheduler.runRepeatingAsync(this::reconcile, reconcileTicks, reconcileTicks);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        logger.log(Level.INFO, () -> String.format(
                "Proxy vote-sync active - outbox fast layer + DB reconcile safety net every %ds.",
                reconcileSeconds));
    }

    /**
     * Applies a batch of foreign outbox events (own events are already filtered out).
     * Any party event triggers a single authoritative DB reconcile of the bar;
     * completions also fire the "party reached" broadcast on this backend.
     */
    private void applyForeign(@NotNull List<VoteSyncEventEntity> events) {
        boolean partyChanged = false;
        List<Integer> completed = new ArrayList<>();
        for (VoteSyncEventEntity event : events) {
            switch (event.getEventType()) {
                case ProxyEventTypes.PARTY_PROGRESS -> partyChanged = true;
                case ProxyEventTypes.PARTY_COMPLETE -> {
                    partyChanged = true;
                    int number = parsePartyNumber(event.getPayload());
                    if (number > 0) {
                        completed.add(number);
                    }
                }
                default -> logger.log(Level.FINE, () -> "Ignoring unknown proxy event: " + event.getEventType());
            }
        }
        if (partyChanged) {
            reconcile();
        }
        for (int number : completed) {
            scheduler.runSync(() -> broadcastService.broadcastPartyReached(number));
        }
    }

    private void reconcile() {
        try {
            votePartyService.reconcileFromDb();
        } catch (Exception ex) {
            logger.log(Level.WARNING, ex, () -> "Proxy vote-sync DB reconcile failed");
        }
    }

    private static int parsePartyNumber(@Nullable String payload) {
        if (payload == null || payload.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(payload.trim());
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    /** On join, refresh the party view so the joining player sees the network-wide count. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        scheduler.runAsync(this::reconcile);
    }
}
