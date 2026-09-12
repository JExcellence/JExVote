package de.jexcellence.vote.database.entity;

import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * One row of the cross-backend vote-sync outbox (the DB-backed event bus that
 * replaces Redis - see {@code de.jexcellence.vote.service.OutboxProxyEventBus}).
 *
 * <p>Each backend on a shared-DB network {@code INSERT}s a row when a
 * network-relevant thing happens (party progressed, party completed) and every
 * backend polls for rows with a higher {@code id} than it has seen, applying the
 * ones it did not originate. The auto-increment {@code id} (from {@link LongIdEntity})
 * is the ordering + watermark; {@code createdAt} drives the retention purge so the
 * table stays tiny (also the DSGVO Art. 5 Abs. 1 lit. e storage-limitation point -
 * these rows carry a player UUID in the payload and are short-lived by design).
 *
 * @author JExcellence
 */
@Entity
@Table(name = "jexvote_sync_event", indexes = {
        @Index(name = "idx_sync_event_created", columnList = "created_at")
})
public class VoteSyncEventEntity extends LongIdEntity {

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "payload", length = 512)
    private String payload;

    @Column(name = "origin_server", nullable = false, length = 64)
    private String originServer;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected VoteSyncEventEntity() {
        // JPA
    }

    public VoteSyncEventEntity(@NotNull String eventType, @Nullable String payload,
                               @NotNull String originServer) {
        this.eventType = eventType;
        this.payload = payload;
        this.originServer = originServer;
        this.createdAt = Instant.now();
    }

    public @NotNull String getEventType() {
        return eventType;
    }

    public @Nullable String getPayload() {
        return payload;
    }

    public @NotNull String getOriginServer() {
        return originServer;
    }

    public @NotNull Instant getCreatedAt() {
        return createdAt;
    }
}
