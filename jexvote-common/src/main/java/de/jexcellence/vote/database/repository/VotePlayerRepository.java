package de.jexcellence.vote.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import de.jexcellence.vote.database.entity.VotePlayerEntity;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class VotePlayerRepository extends AbstractCrudRepository<VotePlayerEntity, Long> {

    public VotePlayerRepository(@NotNull ExecutorService executor,
                                @NotNull EntityManagerFactory emf,
                                @NotNull Class<VotePlayerEntity> entityClass) {
        super(executor, emf, entityClass);
    }

    public @NotNull Optional<VotePlayerEntity> findByUuid(@NotNull UUID uuid) {
        return query().and("playerUuid", uuid).first();
    }

    public @NotNull CompletableFuture<Optional<VotePlayerEntity>> findByUuidAsync(@NotNull UUID uuid) {
        return query().and("playerUuid", uuid).firstAsync();
    }

    public @NotNull CompletableFuture<List<VotePlayerEntity>> findAllAsync() {
        return query().listAsync();
    }

    public @NotNull CompletableFuture<List<VotePlayerEntity>> findTopByTotalVotesAsync(int limit) {
        return query()
                .orderByDesc("totalVotes")
                .limit(limit)
                .listAsync();
    }

    public @NotNull CompletableFuture<List<VotePlayerEntity>> findTopByMonthlyVotesAsync(int limit) {
        return query()
                .orderByDesc("monthlyVotes")
                .limit(limit)
                .listAsync();
    }
}
