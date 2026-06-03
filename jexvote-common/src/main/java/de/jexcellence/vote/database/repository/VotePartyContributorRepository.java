package de.jexcellence.vote.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import de.jexcellence.vote.database.entity.VotePartyContributorEntity;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

public class VotePartyContributorRepository
        extends AbstractCrudRepository<VotePartyContributorEntity, Long> {

    public VotePartyContributorRepository(@NotNull ExecutorService executor,
                                          @NotNull EntityManagerFactory emf,
                                          @NotNull Class<VotePartyContributorEntity> entityClass) {
        super(executor, emf, entityClass);
    }

    public @NotNull List<VotePartyContributorEntity> findByParty(long partyId) {
        return query().and("partyId", partyId).list();
    }

    public @NotNull Optional<VotePartyContributorEntity> findByPartyAndPlayer(long partyId,
                                                                              @NotNull UUID uuid) {
        return query().and("partyId", partyId).and("playerUuid", uuid).first();
    }

    /**
     * Deletes all contributor rows for a completed party.
     *
     * @return the number of deleted rows
     */
    public int deleteByParty(long partyId) {
        return withSession(ctx -> ctx.getEntityManager()
                .createNamedQuery("VotePartyContributor.deleteByParty")
                .setParameter("partyId", partyId)
                .executeUpdate());
    }
}
