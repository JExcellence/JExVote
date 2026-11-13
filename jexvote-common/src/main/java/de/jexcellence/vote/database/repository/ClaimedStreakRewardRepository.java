package de.jexcellence.vote.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import de.jexcellence.vote.database.entity.ClaimedStreakRewardEntity;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

public class ClaimedStreakRewardRepository extends AbstractCrudRepository<ClaimedStreakRewardEntity, Long> {

    private static final String UNIQUE_VIOLATION_STATE = "23505";
    private static final String MYSQL_INTEGRITY_STATE = "23000";
    private static final int MYSQL_DUPLICATE_KEY_CODE = 1062;
    private static final int MAX_CAUSE_DEPTH = 16;

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
     * Creates a claim record. A record that already exists (unique key on player_uuid + milestone_day) counts
     * as written; every other database error is thrown, so a claim that was paid but not saved is never hidden.
     *
     * @throws IllegalStateException when the record could not be written for another reason
     */
    public void createClaim(@NotNull UUID uuid, int milestoneDay, boolean autoClaimed) {
        try {
            create(new ClaimedStreakRewardEntity(uuid, milestoneDay, autoClaimed));
        } catch (Exception ex) {
            if (!isDuplicateKey(ex)) {
                throw new IllegalStateException(
                        "Streak claim day " + milestoneDay + " of " + uuid + " could not be saved", ex);
            }
        }
    }

    /**
     * Whether a failure is a unique-key violation, which for a claim insert means the claim already exists: SQLSTATE
     * 23505 (PostgreSQL, H2) or 23000 with error 1062 (MySQL, MariaDB). Other constraint errors (null, foreign key)
     * are real failures.
     */
    static boolean isDuplicateKey(@NotNull Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLException sql && isDuplicateKeyState(sql)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isDuplicateKeyState(@NotNull SQLException sql) {
        String state = sql.getSQLState();
        return UNIQUE_VIOLATION_STATE.equals(state)
                || MYSQL_INTEGRITY_STATE.equals(state) && sql.getErrorCode() == MYSQL_DUPLICATE_KEY_CODE;
    }
}
