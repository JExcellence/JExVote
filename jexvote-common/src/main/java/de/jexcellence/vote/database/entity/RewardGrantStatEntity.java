package de.jexcellence.vote.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Tracks how many times a named chance/lucky reward has actually been granted.
 * Keyed by the reward's configured {@code id}. Exposes the count via the
 * {@code %jexvote_reward_count_<id>%} placeholder.
 */
@Entity
@Table(name = "jexvote_reward_stats",
        uniqueConstraints = @UniqueConstraint(name = "uq_reward_stat_key", columnNames = "reward_key"))
@NamedQuery(name = "RewardGrantStat.increment",
        query = "UPDATE RewardGrantStatEntity s SET s.timesGranted = s.timesGranted + 1, "
                + "s.lastGrantedAt = :now WHERE s.rewardKey = :key")
public class RewardGrantStatEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reward_key", nullable = false, length = 128)
    private String rewardKey;

    @Column(name = "times_granted", nullable = false)
    private long timesGranted;

    @Column(name = "last_granted_at")
    private Instant lastGrantedAt;

    protected RewardGrantStatEntity() {}

    public RewardGrantStatEntity(String rewardKey) {
        this.rewardKey = rewardKey;
        this.timesGranted = 1;
        this.lastGrantedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getRewardKey() { return rewardKey; }
    public long getTimesGranted() { return timesGranted; }
    public Instant getLastGrantedAt() { return lastGrantedAt; }
}
