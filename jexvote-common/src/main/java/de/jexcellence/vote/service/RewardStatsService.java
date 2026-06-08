package de.jexcellence.vote.service;

import de.jexcellence.vote.database.entity.RewardGrantStatEntity;
import de.jexcellence.vote.database.repository.RewardGrantStatRepository;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks how often named chance/lucky rewards have been granted.
 *
 * <p>Counts are held in memory (authoritative at runtime, read by placeholders)
 * and persisted asynchronously to {@link RewardGrantStatRepository}. The in-memory
 * map is hydrated from the database on startup via {@link #loadAsync()}.
 *
 * @author JExcellence
 */
public class RewardStatsService {

    private final RewardGrantStatRepository repository;
    private final Logger logger;
    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

    public RewardStatsService(@NotNull RewardGrantStatRepository repository, @NotNull Logger logger) {
        this.repository = repository;
        this.logger = logger;
    }

    /**
     * Hydrates the in-memory counters from the database.
     */
    public void loadAsync() {
        repository.findAllAsync().thenAccept(list -> {
            for (RewardGrantStatEntity stat : list) {
                counts.put(stat.getRewardKey(), new AtomicLong(stat.getTimesGranted()));
            }
            logger.log(Level.INFO, () -> String.format("Loaded %d reward stat(s)", list.size()));
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "Failed to load reward stats", ex);
            return null;
        });
    }

    /**
     * Increments the count for {@code key} in memory and persists asynchronously.
     */
    public void trackGrant(@NotNull String key) {
        counts.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        repository.increment(key).exceptionally(ex -> {
            logger.log(Level.WARNING, ex, () -> "Failed to persist reward stat: " + key);
            return null;
        });
    }

    /**
     * Returns the current grant count for {@code key} (0 if unknown).
     */
    public long getCount(@NotNull String key) {
        AtomicLong count = counts.get(key);
        return count != null ? count.get() : 0L;
    }
}
