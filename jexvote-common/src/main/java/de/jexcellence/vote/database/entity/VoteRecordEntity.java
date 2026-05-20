package de.jexcellence.vote.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jexvote_records", indexes = {
        @Index(name = "idx_vote_record_uuid", columnList = "player_uuid"),
        @Index(name = "idx_vote_record_service", columnList = "service_name"),
        @Index(name = "idx_vote_record_time", columnList = "voted_at")
})
@NamedQuery(name = "VoteRecord.findByPlayer",
        query = "SELECT vr FROM VoteRecordEntity vr WHERE vr.playerUuid = :uuid ORDER BY vr.votedAt DESC")
@NamedQuery(name = "VoteRecord.findByPlayerAndService",
        query = "SELECT vr FROM VoteRecordEntity vr WHERE vr.playerUuid = :uuid AND vr.serviceName = :service ORDER BY vr.votedAt DESC")
@NamedQuery(name = "VoteRecord.countByPlayerSince",
        query = "SELECT COUNT(vr) FROM VoteRecordEntity vr WHERE vr.playerUuid = :uuid AND vr.votedAt >= :since")
public class VoteRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_uuid", nullable = false, length = 36)
    private UUID playerUuid;

    @Column(name = "player_name", length = 16)
    private String playerName;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(name = "address")
    private String address;

    @Column(name = "voted_at", nullable = false)
    private Instant votedAt;

    protected VoteRecordEntity() {}

    public VoteRecordEntity(UUID playerUuid, String playerName, String serviceName,
                            String address, Instant votedAt) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.serviceName = serviceName;
        this.address = address;
        this.votedAt = votedAt;
    }

    public Long getId() { return id; }
    public UUID getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }
    public String getServiceName() { return serviceName; }
    public String getAddress() { return address; }
    public Instant getVotedAt() { return votedAt; }
}
