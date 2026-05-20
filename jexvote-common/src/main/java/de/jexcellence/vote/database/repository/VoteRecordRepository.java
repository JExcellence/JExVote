package de.jexcellence.vote.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import de.jexcellence.vote.database.entity.VoteRecordEntity;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class VoteRecordRepository extends AbstractCrudRepository<VoteRecordEntity, Long> {

    public VoteRecordRepository(@NotNull ExecutorService executor,
                                @NotNull EntityManagerFactory emf,
                                @NotNull Class<VoteRecordEntity> entityClass) {
        super(executor, emf, entityClass);
    }

    public @NotNull CompletableFuture<List<VoteRecordEntity>> findByPlayer(@NotNull UUID uuid) {
        return query().and("playerUuid", uuid).listAsync();
    }

    public @NotNull Optional<VoteRecordEntity> findLatestByPlayerAndService(
            @NotNull UUID uuid, @NotNull String serviceName) {
        return query()
                .and("playerUuid", uuid)
                .and("serviceName", serviceName)
                .first();
    }

    public @NotNull CompletableFuture<List<VoteRecordEntity>> findAllAsync() {
        return query().listAsync();
    }
}
