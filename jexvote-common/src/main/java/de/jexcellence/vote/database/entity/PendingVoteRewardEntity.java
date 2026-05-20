package de.jexcellence.vote.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jexvote_pending_rewards", indexes = {
        @Index(name = "idx_pending_reward_uuid", columnList = "player_uuid")
})
@NamedQuery(name = "PendingVoteReward.findByPlayer",
        query = "SELECT pr FROM PendingVoteRewardEntity pr WHERE pr.playerUuid = :uuid ORDER BY pr.createdAt ASC")
@NamedQuery(name = "PendingVoteReward.deleteByPlayer",
        query = "DELETE FROM PendingVoteRewardEntity pr WHERE pr.playerUuid = :uuid")
public class PendingVoteRewardEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_uuid", nullable = false, length = 36)
    private UUID playerUuid;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Lob
    @Column(name = "reward_data", nullable = false)
    private String rewardData;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PendingVoteRewardEntity() {}

    public PendingVoteRewardEntity(UUID playerUuid, String serviceName, String rewardData) {
        this.playerUuid = playerUuid;
        this.serviceName = serviceName;
        this.rewardData = rewardData;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getPlayerUuid() { return playerUuid; }
    public String getServiceName() { return serviceName; }
    public String getRewardData() { return rewardData; }
    public Instant getCreatedAt() { return createdAt; }
}
