package de.jexcellence.vote;

import de.jexcellence.vote.model.VoteSite;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoteEditionTest {

    @Test
    void freeEditionKeepsTheFirstFiveSitesInConfigOrder() {
        Map<String, VoteSite> sites = sites(7);

        Map<String, VoteSite> loaded = new VoteEdition.FreeEdition().limitSites(sites);

        assertEquals(List.of("site-1", "site-2", "site-3", "site-4", "site-5"), List.copyOf(loaded.keySet()));
    }

    @Test
    void freeEditionWithinTheLimitKeepsEverySite() {
        Map<String, VoteSite> sites = sites(3);

        assertSame(sites, new VoteEdition.FreeEdition().limitSites(sites));
    }

    @Test
    void premiumHasNoLimit() {
        VoteEdition premium = new VoteEdition.PremiumEdition();
        Map<String, VoteSite> sites = sites(12);

        assertFalse(premium.hasSiteLimit());
        assertEquals(12, premium.limitSites(sites).size());
    }

    @Test
    void freeEditionGatesPremiumFeatures() {
        VoteEdition free = new VoteEdition.FreeEdition();

        assertTrue(free.hasSiteLimit());
        assertFalse(free.voteShopEnabled());
        assertFalse(free.votePartyEnabled());
        assertFalse(free.weekendMultiplierEnabled());
    }

    private static Map<String, VoteSite> sites(int count) {
        Map<String, VoteSite> sites = new LinkedHashMap<>();
        for (int i = 1; i <= count; i++) {
            String id = "site-" + i;
            sites.put(id, new VoteSite(id, "Site " + i, "Service" + i, null, Duration.ofDays(1), null,
                    ZoneId.of("UTC"), 1, null, null, "1"));
        }
        return sites;
    }
}
