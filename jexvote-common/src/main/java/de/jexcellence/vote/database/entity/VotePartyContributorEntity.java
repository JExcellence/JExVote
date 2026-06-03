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

import java.util.UUID;

/**
 * Tracks how many votes a player contributed to a specific vote party. Rows are
 * deleted when the party completes. The unique constraint keeps one row per
 * (party, player) so contributions accumulate via update rather than insert.
 */
@Entity
@Table(name = "jexvote_party_contributors",
        uniqueConstraints = @UniqueConstraint(name = "uq_party_contributor",
                columnNames = {"party_id", "player_uuid"}),
        indexes = @Index(name = "idx_party_contributor_party", columnList = "party_id"))
@NamedQuery(name = "VotePartyContributor.deleteByParty",
        query = "DELETE FROM VotePartyContributorEntity c WHERE c.partyId = :partyId")
public class VotePartyContributorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "party_id", nullable = false)
    private long partyId;

    @Column(name = "player_uuid", nullable = false, length = 36)
    private UUID playerUuid;

    @Column(name = "contributed_votes", nullable = false)
    private int contributedVotes;

    protected VotePartyContributorEntity() {}

    public VotePartyContributorEntity(long partyId, UUID playerUuid) {
        this.partyId = partyId;
        this.playerUuid = playerUuid;
        this.contributedVotes = 1;
    }

    public Long getId() { return id; }
    public long getPartyId() { return partyId; }
    public UUID getPlayerUuid() { return playerUuid; }
    public int getContributedVotes() { return contributedVotes; }

    public void setContributedVotes(int contributedVotes) { this.contributedVotes = contributedVotes; }
}
