package de.jexcellence.vote.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.ColumnDefault;

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
    @ColumnDefault("0")
    private int totalVotes;

    @Column(name = "monthly_votes", nullable = false)
    @ColumnDefault("0")
    private int monthlyVotes;

    @Column(name = "current_streak", nullable = false)
    @ColumnDefault("0")
    private int currentStreak;

    @Column(name = "highest_streak", nullable = false)
    @ColumnDefault("0")
    private int highestStreak;

    @Column(name = "vote_points", nullable = false)
    @ColumnDefault("0")
    private int votePoints;

    @Column(name = "last_vote_at")
    private Instant lastVoteAt;

    @Column(name = "monthly_reset_month", length = 7)
    private String monthlyResetMonth;

    /**
     * Number of Streak Freezes currently owned (Duolingo-style auto-equip).
     * {@link ColumnDefault} provides a SQL default so the column can be added
     * to an existing table that already has rows (otherwise the NOT NULL
     * add-column migration fails on those rows).
     */
    @Column(name = "streak_freezes", nullable = false)
    @ColumnDefault("0")
    private int streakFreezes;

    /**
     * Whether the one-time free Streak Freeze grant has been applied to this
     * profile. Guards the idempotent free-freeze migration for existing players.
     * Defaults to {@code false} so existing rows receive the back-fill.
     */
    @Column(name = "freeze_initialized", nullable = false)
    @ColumnDefault("false")
    private boolean freezeInitialized;

    /** Day (ISO yyyy-MM-dd, in the configured gift timezone) of the last gift sent. */
    @Column(name = "gift_day", length = 10)
    private String giftDay;

    /** Number of gifts already sent during {@link #giftDay}. */
    @Column(name = "gifts_today", nullable = false)
    @ColumnDefault("0")
    private int giftsToday;

    /** Day (ISO yyyy-MM-dd) of the last daily fly coupon grant. */
    @Column(name = "daily_fly_date", length = 10)
    private String dailyFlyDate;

    /**
     * Number of Streak Freezes auto-consumed during the vote currently being
     * processed. Not persisted — read once by the delivery step to notify the
     * player that their streak was saved.
     */
    @Transient
    private int consumedFreezesThisVote;

    /**
     * Number of free Streak Freezes just granted on profile creation (first
     * vote). Not persisted — read once by the delivery step to congratulate
     * the player on their welcome freeze.
     */
    @Transient
    private int freshFreezeGrant;

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

    public int getStreakFreezes() { return streakFreezes; }
    public void setStreakFreezes(int streakFreezes) { this.streakFreezes = streakFreezes; }

    public boolean isFreezeInitialized() { return freezeInitialized; }
    public void setFreezeInitialized(boolean freezeInitialized) { this.freezeInitialized = freezeInitialized; }

    public String getGiftDay() { return giftDay; }
    public void setGiftDay(String giftDay) { this.giftDay = giftDay; }

    public int getGiftsToday() { return giftsToday; }
    public void setGiftsToday(int giftsToday) { this.giftsToday = giftsToday; }

    public String getDailyFlyDate() { return dailyFlyDate; }
    public void setDailyFlyDate(String dailyFlyDate) { this.dailyFlyDate = dailyFlyDate; }

    public int getConsumedFreezesThisVote() { return consumedFreezesThisVote; }
    public void setConsumedFreezesThisVote(int consumedFreezesThisVote) { this.consumedFreezesThisVote = consumedFreezesThisVote; }

    public int getFreshFreezeGrant() { return freshFreezeGrant; }
    public void setFreshFreezeGrant(int freshFreezeGrant) { this.freshFreezeGrant = freshFreezeGrant; }
}
