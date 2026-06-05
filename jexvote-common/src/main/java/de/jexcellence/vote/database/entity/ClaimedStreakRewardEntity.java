package de.jexcellence.vote.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jexvote_claimed_streaks",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_claimed_streak_player_day",
                columnNames = {"player_uuid", "milestone_day"}),
        indexes = {
                @Index(name = "idx_claimed_streak_uuid", columnList = "player_uuid")
        })
@NamedQuery(name = "ClaimedStreakReward.findByPlayer",
        query = "SELECT c FROM ClaimedStreakRewardEntity c WHERE c.playerUuid = :uuid ORDER BY c.milestoneDay ASC")
@NamedQuery(name = "ClaimedStreakReward.findByPlayerAndDay",
        query = "SELECT c FROM ClaimedStreakRewardEntity c WHERE c.playerUuid = :uuid AND c.milestoneDay = :day")
public class ClaimedStreakRewardEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_uuid", nullable = false, length = 36)
    private UUID playerUuid;

    @Column(name = "milestone_day", nullable = false)
    @ColumnDefault("0")
    private int milestoneDay;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    @Column(name = "auto_claimed", nullable = false)
    @ColumnDefault("false")
    private boolean autoClaimed;

    protected ClaimedStreakRewardEntity() {}

    public ClaimedStreakRewardEntity(UUID playerUuid, int milestoneDay, boolean autoClaimed) {
        this.playerUuid = playerUuid;
        this.milestoneDay = milestoneDay;
        this.claimedAt = Instant.now();
        this.autoClaimed = autoClaimed;
    }

    public Long getId() { return id; }
    public UUID getPlayerUuid() { return playerUuid; }
    public int getMilestoneDay() { return milestoneDay; }
    public Instant getClaimedAt() { return claimedAt; }
    public boolean isAutoClaimed() { return autoClaimed; }
}
