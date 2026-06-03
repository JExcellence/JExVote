package de.jexcellence.vote;

public sealed interface VoteEdition {

    String name();

    int maxVoteSites();

    boolean voteShopEnabled();

    boolean leaderboardGuiEnabled();

    boolean streakBonusEnabled();

    boolean votePartyEnabled();

    boolean weekendMultiplierEnabled();

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
    }
}
