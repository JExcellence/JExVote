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
@Table(name = "jexvote_players", indexes = {
        @Index(name = "idx_vote_player_uuid", columnList = "player_uuid", unique = true),
        @Index(name = "idx_vote_player_total", columnList = "total_votes"),
        @Index(name = "idx_vote_player_monthly", columnList = "monthly_votes")
})
@NamedQuery(name = "VotePlayer.findByUuid",
        query = "SELECT vp FROM VotePlayerEntity vp WHERE vp.playerUuid = :uuid")
@NamedQuery(name = "VotePlayer.findAll",
        query = "SELECT vp FROM VotePlayerEntity vp")
@NamedQuery(name = "VotePlayer.topByTotal",
        query = "SELECT vp FROM VotePlayerEntity vp ORDER BY vp.totalVotes DESC")
@NamedQuery(name = "VotePlayer.topByMonthly",
        query = "SELECT vp FROM VotePlayerEntity vp ORDER BY vp.monthlyVotes DESC")
@NamedQuery(name = "VotePlayer.topByStreak",
        query = "SELECT vp FROM VotePlayerEntity vp ORDER BY vp.highestStreak DESC")
public class VotePlayerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_uuid", nullable = false, unique = true, length = 36)
    private UUID playerUuid;

    @Column(name = "player_name", length = 16)
    private String playerName;

    @Column(name = "total_votes", nullable = false)
    private int totalVotes;

    @Column(name = "monthly_votes", nullable = false)
    private int monthlyVotes;

    @Column(name = "current_streak", nullable = false)
    private int currentStreak;

    @Column(name = "highest_streak", nullable = false)
    private int highestStreak;

    @Column(name = "vote_points", nullable = false)
    private int votePoints;

    @Column(name = "last_vote_at")
    private Instant lastVoteAt;

    @Column(name = "monthly_reset_month", length = 7)
    private String monthlyResetMonth;

    protected VotePlayerEntity() {}

    public VotePlayerEntity(UUID playerUuid, String playerName) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.totalVotes = 0;
        this.monthlyVotes = 0;
        this.currentStreak = 0;
        this.highestStreak = 0;
        this.votePoints = 0;
    }

    public Long getId() { return id; }

    public UUID getPlayerUuid() { return playerUuid; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }

    public int getTotalVotes() { return totalVotes; }
    public void setTotalVotes(int totalVotes) { this.totalVotes = totalVotes; }

    public int getMonthlyVotes() { return monthlyVotes; }
    public void setMonthlyVotes(int monthlyVotes) { this.monthlyVotes = monthlyVotes; }

    public int getCurrentStreak() { return currentStreak; }
    public void setCurrentStreak(int currentStreak) { this.currentStreak = currentStreak; }

    public int getHighestStreak() { return highestStreak; }
    public void setHighestStreak(int highestStreak) { this.highestStreak = highestStreak; }

    public int getVotePoints() { return votePoints; }
    public void setVotePoints(int votePoints) { this.votePoints = votePoints; }

    public Instant getLastVoteAt() { return lastVoteAt; }
    public void setLastVoteAt(Instant lastVoteAt) { this.lastVoteAt = lastVoteAt; }

    public String getMonthlyResetMonth() { return monthlyResetMonth; }
    public void setMonthlyResetMonth(String monthlyResetMonth) { this.monthlyResetMonth = monthlyResetMonth; }
}
