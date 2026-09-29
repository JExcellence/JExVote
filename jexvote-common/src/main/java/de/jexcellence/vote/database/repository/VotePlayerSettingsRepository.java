package de.jexcellence.vote.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import de.jexcellence.vote.database.entity.VotePlayerSettingsEntity;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Reads and writes the per-player vote settings rows.
 *
 * @author JExcellence
 * @since 3.4.0
 */
public class VotePlayerSettingsRepository extends AbstractCrudRepository<VotePlayerSettingsEntity, Long> {

    public VotePlayerSettingsRepository(@NotNull ExecutorService executor,
                                        @NotNull EntityManagerFactory emf,
                                        @NotNull Class<VotePlayerSettingsEntity> entityClass) {
        super(executor, emf, entityClass);
    }

    public @NotNull Optional<VotePlayerSettingsEntity> findByUuid(@NotNull UUID uuid) {
        return query().and("playerUuid", uuid).first();
    }

    public @NotNull CompletableFuture<Optional<VotePlayerSettingsEntity>> findByUuidAsync(@NotNull UUID uuid) {
        return query().and("playerUuid", uuid).firstAsync();
    }

    /** @return every row whose player turned the Discord reminder on. */
    public @NotNull CompletableFuture<List<VotePlayerSettingsEntity>> findDiscordReminderAsync() {
        return query().and("discordReminder", Boolean.TRUE).listAsync();
    }
}
