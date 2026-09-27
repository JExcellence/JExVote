package de.jexcellence.vote.config;

import de.jexcellence.vote.VoteEdition;
import org.jetbrains.annotations.NotNull;

/**
 * Which player-facing features are live right now: the config switch and the edition gate combined. Menus,
 * commands and Bedrock forms ask this class, so a feature that is off in {@code config.yml} or not part of the
 * edition is hidden everywhere in the same way. Reads the config live, so {@code /jexvote reload} applies.
 *
 * @author JExcellence
 * @since 3.3.0
 */
public final class VoteFeatures {

    /** Why a feature is on or off, for the admin status panel. */
    public enum State {
        /** Turned on and part of the edition. */
        ON,
        /** Switched off in the config. */
        OFF,
        /** Not part of the Free edition. */
        PREMIUM_ONLY
    }

    private final VoteEdition edition;
    private final VoteConfig config;

    public VoteFeatures(@NotNull VoteEdition edition, @NotNull VoteConfig config) {
        this.edition = edition;
        this.config = config;
    }

    /** @return the running edition. */
    public @NotNull VoteEdition edition() {
        return edition;
    }

    /** @return whether streak tracking and milestones are on. */
    public boolean streaks() {
        return config.isFeatureStreaks();
    }

    /** @return whether the leaderboard menu is on. */
    public boolean leaderboard() {
        return edition.leaderboardGuiEnabled() && config.isFeatureLeaderboard();
    }

    /** @return whether the vote shop is on. */
    public boolean shop() {
        return state(edition.voteShopEnabled(), config.isFeatureShop()) == State.ON;
    }

    /** @return whether the weekend bonus is on. */
    public boolean weekendBonus() {
        return state(edition.weekendMultiplierEnabled(), config.isWeekendMultiplierEnabled()) == State.ON;
    }

    /** @return whether the vote party is switched on (the party service still needs a restart to start). */
    public boolean party() {
        return state(edition.votePartyEnabled(), config.isVotePartyEnabled()) == State.ON;
    }

    /** @return whether Streak Freezes can be bought and used; they protect a streak, so streaks must be on. */
    public boolean freezes() {
        return streaks() && config.getFreezeSettings().enabled();
    }

    /** @return whether vote gifts can be sent; a gift advances a streak, so streaks must be on. */
    public boolean gifts() {
        return streaks() && config.getGiftSettings().enabled();
    }

    /** @return the admin state of the shop. */
    public @NotNull State shopState() {
        return state(edition.voteShopEnabled(), config.isFeatureShop());
    }

    /** @return the admin state of the vote party. */
    public @NotNull State partyState() {
        return state(edition.votePartyEnabled(), config.isVotePartyEnabled());
    }

    /** @return the admin state of the weekend bonus. */
    public @NotNull State weekendBonusState() {
        return state(edition.weekendMultiplierEnabled(), config.isWeekendMultiplierEnabled());
    }

    /**
     * Combines the edition gate and the config switch.
     *
     * @param editionAllows whether the edition contains the feature
     * @param configOn      whether the config switch is on
     * @return the resulting state; the edition gate wins
     */
    public static @NotNull State state(boolean editionAllows, boolean configOn) {
        if (!editionAllows) {
            return State.PREMIUM_ONLY;
        }
        return configOn ? State.ON : State.OFF;
    }
}
