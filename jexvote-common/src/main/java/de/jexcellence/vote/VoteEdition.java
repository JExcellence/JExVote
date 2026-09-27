package de.jexcellence.vote;

import de.jexcellence.vote.model.VoteSite;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an edition of JExVote may do. Free is capped at {@link #maxVoteSites()} sites and has no shop, vote
 * party, weekend bonus, reward SPI, proxy sync or API write hooks; Premium has everything.
 */
public sealed interface VoteEdition {

    String name();

    int maxVoteSites();

    boolean voteShopEnabled();

    boolean leaderboardGuiEnabled();

    boolean streakBonusEnabled();

    boolean votePartyEnabled();

    boolean weekendMultiplierEnabled();

    /** V2 reward-provider SPI - heavy capability, Premium only (default off). */
    default boolean rewardSpiEnabled() { return false; }

    /** V3/V4 proxy-aware vote sync - heavy capability, Premium only (default off). */
    default boolean proxySyncEnabled() { return false; }

    /** V1.2 API write-hooks (grant points / force streak-grace / trigger party) - Premium only. */
    default boolean writeHooksEnabled() { return false; }

    /** @return whether this edition caps the number of vote sites. */
    default boolean hasSiteLimit() {
        return maxVoteSites() > 0;
    }

    /**
     * Keeps the first {@link #maxVoteSites()} sites in config order and drops the rest. Applied on start and on
     * {@code /jexvote reload}, so the Free limit holds either way.
     *
     * @param sites the configured sites in config order
     * @return the sites this edition loads
     */
    default @NotNull Map<String, VoteSite> limitSites(@NotNull Map<String, VoteSite> sites) {
        if (!hasSiteLimit() || sites.size() <= maxVoteSites()) {
            return sites;
        }
        Map<String, VoteSite> limited = new LinkedHashMap<>();
        sites.entrySet().stream()
                .limit(maxVoteSites())
                .forEach(entry -> limited.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(limited);
    }

    record FreeEdition() implements VoteEdition {
        @Override public String name() { return "Free"; }
        @Override public int maxVoteSites() { return 5; }
        @Override public boolean voteShopEnabled() { return false; }
        @Override public boolean leaderboardGuiEnabled() { return true; }
        @Override public boolean streakBonusEnabled() { return true; }
        @Override public boolean votePartyEnabled() { return false; }
        @Override public boolean weekendMultiplierEnabled() { return false; }
    }

    record PremiumEdition() implements VoteEdition {
        @Override public String name() { return "Premium"; }
        @Override public int maxVoteSites() { return -1; }
        @Override public boolean voteShopEnabled() { return true; }
        @Override public boolean leaderboardGuiEnabled() { return true; }
        @Override public boolean streakBonusEnabled() { return true; }
        @Override public boolean votePartyEnabled() { return true; }
        @Override public boolean weekendMultiplierEnabled() { return true; }
        @Override public boolean rewardSpiEnabled() { return true; }
        @Override public boolean proxySyncEnabled() { return true; }
        @Override public boolean writeHooksEnabled() { return true; }
    }
}
