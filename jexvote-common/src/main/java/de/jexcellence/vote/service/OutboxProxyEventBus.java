package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.database.entity.VoteSyncEventEntity;
import de.jexcellence.vote.database.repository.VoteSyncEventRepository;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A cross-backend event bus built on the shared database - the self-hosted,
 * dependency-free alternative to Redis pub/sub (the plan's V4 optional accelerator,
 * done without third-party infrastructure).
 *
 * <p>How it works (the outbox pattern): each backend {@code INSERT}s a small row
 * into {@link VoteSyncEventEntity} when a network-relevant event occurs, and every
 * backend polls for rows newer than the last id it saw, applying the ones it did
 * not originate. The auto-increment id is both the ordering and the per-backend
 * watermark, so a poll is one index-covered range scan that returns nothing on a
 * quiet server. A retention purge keeps the table tiny.
 *
 * <p>Latency is the poll interval (default ~2s) - imperceptible for a vote-party
 * bar or a "party reached" broadcast, and with none of Redis's operational cost.
 * The DB stays the single source of truth <b>and</b> the transport; this bus only
 * makes the reaction near-instant instead of waiting for the slower reconcile poll.
 *
 * @author JExcellence
 */
public final class OutboxProxyEventBus implements ProxyEventPublisher {

    private static final long PURGE_PERIOD_TICKS = 20L * 60L; // every 60s

    private final PlatformScheduler scheduler;
    private final Logger logger;
    private final VoteSyncEventRepository repository;
    private final String serverId;
    private final int eventPollSeconds;
    private final int retentionMinutes;

    private final AtomicLong lastSeenId = new AtomicLong(0L);
    private @Nullable Consumer<List<VoteSyncEventEntity>> foreignHandler;

    public OutboxProxyEventBus(@NotNull JavaPlugin plugin,
                               @NotNull VoteSyncEventRepository repository,
                               @NotNull String serverId,
                               int eventPollSeconds,
                               int retentionMinutes) {
        this.scheduler = PlatformScheduler.of(plugin);
        this.logger = plugin.getLogger();
        this.repository = repository;
        this.serverId = serverId;
        this.eventPollSeconds = Math.max(1, eventPollSeconds);
        this.retentionMinutes = Math.max(1, retentionMinutes);
    }

    /**
     * Baselines the watermark to the current max id (so a backend never replays
     * history on startup), then starts the poll + purge loops.
     *
     * @param handler receives each batch of <b>foreign</b> events (own events filtered out)
     */
    public void start(@NotNull Consumer<List<VoteSyncEventEntity>> handler) {
        this.foreignHandler = handler;
        this.lastSeenId.set(repository.currentMaxId());
        long pollTicks = (long) eventPollSeconds * 20L;
        scheduler.runRepeatingAsync(this::poll, pollTicks, pollTicks);
        scheduler.runRepeatingAsync(this::purge, PURGE_PERIOD_TICKS, PURGE_PERIOD_TICKS);
        logger.log(Level.INFO, () -> String.format(
                "Vote-sync outbox active (server-id %s) - polling every %ds, retaining %dmin.",
                serverId, eventPollSeconds, retentionMinutes));
    }

    @Override
    public void publish(@NotNull String type, @Nullable String payload) {
        try {
            repository.create(new VoteSyncEventEntity(type, payload, serverId));
        } catch (Exception ex) {
            logger.log(Level.WARNING, ex, () -> "vote-sync outbox publish failed: " + type);
        }
    }

    private void poll() {
        Consumer<List<VoteSyncEventEntity>> handler = this.foreignHandler;
        if (handler == null) {
            return;
        }
        try {
            List<VoteSyncEventEntity> batch = repository.findNewerThan(lastSeenId.get());
            if (batch.isEmpty()) {
                return;
            }
            long maxId = lastSeenId.get();
            List<VoteSyncEventEntity> foreign = new ArrayList<>();
            for (VoteSyncEventEntity event : batch) {
                maxId = Math.max(maxId, event.getId());
                if (!serverId.equals(event.getOriginServer())) {
                    foreign.add(event);
                }
            }
            lastSeenId.set(maxId);
            if (!foreign.isEmpty()) {
                handler.accept(foreign);
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, ex, () -> "vote-sync outbox poll failed");
        }
    }

    private void purge() {
        try {
            int removed = repository.deleteOlderThan(Instant.now().minus(Duration.ofMinutes(retentionMinutes)));
            if (removed > 0) {
                logger.log(Level.FINE, () -> String.format("vote-sync outbox purged %d old event(s)", removed));
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, ex, () -> "vote-sync outbox purge failed");
        }
    }
}
