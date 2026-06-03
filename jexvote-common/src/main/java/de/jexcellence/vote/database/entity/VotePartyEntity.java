package de.jexcellence.vote.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The single active vote party. A shared, server-wide counter that, once it
 * reaches {@code targetVotes}, rewards every contributor and resets for the next
 * party. Exactly one row is expected to exist at a time.
 */
@Entity
@Table(name = "jexvote_party")
public class VotePartyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "party_number", nullable = false)
    private int partyNumber;

    @Column(name = "current_votes", nullable = false)
    private int currentVotes;

    @Column(name = "target_votes", nullable = false)
    private int targetVotes;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    protected VotePartyEntity() {}

    public VotePartyEntity(int partyNumber, int targetVotes) {
        this.partyNumber = partyNumber;
        this.currentVotes = 0;
        this.targetVotes = targetVotes;
        this.startedAt = Instant.now();
    }

    public Long getId() { return id; }
    public int getPartyNumber() { return partyNumber; }
    public int getCurrentVotes() { return currentVotes; }
    public int getTargetVotes() { return targetVotes; }
    public Instant getStartedAt() { return startedAt; }

    public void setPartyNumber(int partyNumber) { this.partyNumber = partyNumber; }
    public void setCurrentVotes(int currentVotes) { this.currentVotes = currentVotes; }
    public void setTargetVotes(int targetVotes) { this.targetVotes = targetVotes; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
}
