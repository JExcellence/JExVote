package de.jexcellence.vote.service;

/**
 * Event-type identifiers for the cross-backend vote-sync outbox. Constants so the
 * producer ({@link VotePartyService}) and consumer ({@link ProxyVoteSyncService})
 * agree on the wire values without duplicating literals.
 *
 * @author JExcellence
 */
public final class ProxyEventTypes {

    /** A vote advanced the network vote-party; payload {@code "current:target"}. */
    public static final String PARTY_PROGRESS = "PARTY_PROGRESS";

    /** The network vote-party completed and reset; payload = the completed party number. */
    public static final String PARTY_COMPLETE = "PARTY_COMPLETE";

    private ProxyEventTypes() {
        // Constants holder - no instances.
    }
}
