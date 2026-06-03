package de.jexcellence.vote.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import de.jexcellence.vote.database.entity.VotePartyEntity;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.concurrent.ExecutorService;

public class VotePartyRepository extends AbstractCrudRepository<VotePartyEntity, Long> {

    public VotePartyRepository(@NotNull ExecutorService executor,
                               @NotNull EntityManagerFactory emf,
                               @NotNull Class<VotePartyEntity> entityClass) {
        super(executor, emf, entityClass);
    }

    /**
     * Returns the single active party row, if one exists. Exactly one row is
     * expected during normal operation.
     */
    public @NotNull Optional<VotePartyEntity> findActive() {
        return query().orderByDesc("id").first();
    }
}
