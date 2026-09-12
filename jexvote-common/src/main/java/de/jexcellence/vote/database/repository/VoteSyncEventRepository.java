package de.jexcellence.vote.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import de.jexcellence.vote.database.entity.VoteSyncEventEntity;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Access to the cross-backend vote-sync outbox ({@link VoteSyncEventEntity}). All
 * methods here are synchronous - the proxy bus calls them from its own async
 * scheduler thread, so blocking is intended.
 *
 * @author JExcellence
 */
public class VoteSyncEventRepository extends AbstractCrudRepository<VoteSyncEventEntity, Long> {

    private static final int MAX_BATCH = 500;

    public VoteSyncEventRepository(@NotNull ExecutorService executor,
                                   @NotNull EntityManagerFactory emf,
                                   @NotNull Class<VoteSyncEventEntity> entityClass) {
        super(executor, emf, entityClass);
    }

    /**
     * Events with an id greater than {@code lastSeenId}, oldest first, capped at
     * {@link #MAX_BATCH}. The id is the auto-increment PK, so this is an
     * index-covered range scan that returns nothing on a quiet server.
     */
    public @NotNull List<VoteSyncEventEntity> findNewerThan(long lastSeenId) {
        return withSession(ctx -> ctx.getEntityManager()
                .createQuery(
                        "SELECT e FROM VoteSyncEventEntity e WHERE e.id > :lastSeenId ORDER BY e.id ASC",
                        VoteSyncEventEntity.class)
                .setParameter("lastSeenId", lastSeenId)
                .setMaxResults(MAX_BATCH)
                .getResultList());
    }

    /** The highest event id currently stored, or {@code 0} when the table is empty. */
    public long currentMaxId() {
        Long max = withSession(ctx -> ctx.getEntityManager()
                .createQuery("SELECT COALESCE(MAX(e.id), 0) FROM VoteSyncEventEntity e", Long.class)
                .getSingleResult());
        return max == null ? 0L : max;
    }

    /**
     * Deletes events older than {@code cutoff} (retention purge).
     *
     * @return the number of rows removed
     */
    public int deleteOlderThan(@NotNull Instant cutoff) {
        return withSession(ctx -> ctx.getEntityManager()
                .createQuery("DELETE FROM VoteSyncEventEntity e WHERE e.createdAt < :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate());
    }
}
