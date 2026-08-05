package de.jexcellence.vote.database.entity;

import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
public class PendingVoteRewardEntity extends LongIdEntity {

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

    public UUID getPlayerUuid() { return playerUuid; }
    public String getServiceName() { return serviceName; }
    public String getRewardData() { return rewardData; }
    public Instant getCreatedAt() { return createdAt; }
}
