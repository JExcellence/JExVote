package de.jexcellence.vote.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import de.jexcellence.vote.database.entity.RewardGrantStatEntity;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class RewardGrantStatRepository extends AbstractCrudRepository<RewardGrantStatEntity, Long> {

    public RewardGrantStatRepository(@NotNull ExecutorService executor,
                                     @NotNull EntityManagerFactory emf,
                                     @NotNull Class<RewardGrantStatEntity> entityClass) {
        super(executor, emf, entityClass);
    }

    public @NotNull CompletableFuture<List<RewardGrantStatEntity>> findAllAsync() {
        return query().listAsync();
    }

    /**
     * Atomically increments the grant count for {@code rewardKey}, inserting the
     * row on first use. Runs off the calling thread.
     */
    public @NotNull CompletableFuture<Void> increment(@NotNull String rewardKey) {
        return CompletableFuture.runAsync(() -> withSessionVoid(ctx -> {
            int updated = ctx.getEntityManager()
                    .createNamedQuery("RewardGrantStat.increment")
                    .setParameter("now", Instant.now())
                    .setParameter("key", rewardKey)
                    .executeUpdate();
            if (updated == 0) {
                try {
                    ctx.getEntityManager().persist(new RewardGrantStatEntity(rewardKey));
                } catch (Exception ignored) {
                    // Race: another thread inserted the same key first — safe to ignore,
                    // the in-memory counter remains authoritative at runtime.
                }
            }
        }));
    }
}
