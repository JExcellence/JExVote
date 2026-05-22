package de.jexcellence.vote.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import de.jexcellence.vote.database.entity.ClaimedStreakRewardEntity;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

public class ClaimedStreakRewardRepository extends AbstractCrudRepository<ClaimedStreakRewardEntity, Long> {

    public ClaimedStreakRewardRepository(@NotNull ExecutorService executor,
                                         @NotNull EntityManagerFactory emf,
                                         @NotNull Class<ClaimedStreakRewardEntity> entityClass) {
        super(executor, emf, entityClass);
    }

    public @NotNull CompletableFuture<List<ClaimedStreakRewardEntity>> findByPlayer(@NotNull UUID uuid) {
        return query().and("playerUuid", uuid).listAsync();
    }

    public @NotNull CompletableFuture<Set<Integer>> findClaimedDays(@NotNull UUID uuid) {
        return findByPlayer(uuid).thenApply(list ->
                list.stream()
                        .map(ClaimedStreakRewardEntity::getMilestoneDay)
                        .collect(Collectors.toSet()));
    }

    /**
     * Creates a claim record. Silently ignores duplicate entries
     * (unique constraint on player_uuid + milestone_day).
     */
    public void createClaim(@NotNull UUID uuid, int milestoneDay, boolean autoClaimed) {
        try {
            create(new ClaimedStreakRewardEntity(uuid, milestoneDay, autoClaimed));
        } catch (Exception ignored) {
            // Unique constraint violation — already claimed, safe to ignore
        }
    }
}
