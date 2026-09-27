package de.jexcellence.vote.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteRewardLookupTest {

    private static final Map<String, List<String>> BY_SITE = Map.of(
            "topg", List.of("site-id-reward"),
            "mcsl", List.of("service-name-reward"));

    @Test
    void siteIdKeyAppliesToTheSiteServiceName() {
        assertEquals(List.of("site-id-reward"), SiteRewardLookup.find(BY_SITE, "TopG.org", "topg"));
    }

    @Test
    void serviceNameKeyStillWorks() {
        assertEquals(List.of("service-name-reward"), SiteRewardLookup.find(BY_SITE, "MCSL", "minecraft-server-list"));
    }

    @Test
    void siteIdIsMatchedCaseInsensitively() {
        assertEquals(List.of("site-id-reward"), SiteRewardLookup.find(BY_SITE, "TopG.org", "TopG"));
    }

    @Test
    void unknownSiteHasNoExtras() {
        assertTrue(SiteRewardLookup.find(BY_SITE, "Other", null).isEmpty());
        assertTrue(SiteRewardLookup.find(BY_SITE, "Other", "other").isEmpty());
    }
}
