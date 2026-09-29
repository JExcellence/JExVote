package de.jexcellence.vote.service;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * What a single online player wants to see and hear from JExVote. {@link VoteBroadcastService} asks this before
 * it sends a broadcast, a party announcement or the vote effects to someone.
 *
 * @author JExcellence
 * @since 3.4.0
 */
public interface VotePreferences {

    /** Preferences that let everything through, used until the player settings are wired. */
    VotePreferences EVERYTHING = new VotePreferences() {
        @Override public boolean showsBroadcasts(@NotNull UUID viewer) { return true; }
        @Override public boolean showsPartyAnnouncements(@NotNull UUID viewer) { return true; }
        @Override public boolean playsEffects(@NotNull UUID player) { return true; }
    };

    /** @return whether {@code viewer} sees other players' "voted" broadcasts. */
    boolean showsBroadcasts(@NotNull UUID viewer);

    /** @return whether {@code viewer} sees vote party announcements. */
    boolean showsPartyAnnouncements(@NotNull UUID viewer);

    /** @return whether the vote sounds and titles play for {@code player}'s own votes. */
    boolean playsEffects(@NotNull UUID player);
}
