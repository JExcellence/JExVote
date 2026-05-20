package de.jexcellence.vote.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import de.jexcellence.vote.database.entity.PendingVoteRewardEntity;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class PendingVoteRewardRepository extends AbstractCrudRepository<PendingVoteRewardEntity, Long> {

    public PendingVoteRewardRepository(@NotNull ExecutorService executor,
                                       @NotNull EntityManagerFactory emf,
                                       @NotNull Class<PendingVoteRewardEntity> entityClass) {
        super(executor, emf, entityClass);
    }

    public @NotNull CompletableFuture<List<PendingVoteRewardEntity>> findByPlayer(@NotNull UUID uuid) {
        return query().and("playerUuid", uuid).listAsync();
    }
}
