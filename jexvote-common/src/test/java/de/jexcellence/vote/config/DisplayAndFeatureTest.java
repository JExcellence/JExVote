package de.jexcellence.vote.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayAndFeatureTest {

    @Test
    void autoStyleUsesIconsOnlyWithJExEconomy() {
        assertTrue(CurrencyDisplay.resolve(CurrencyDisplay.Style.AUTO, true, Map.of()).icons());
        assertFalse(CurrencyDisplay.resolve(CurrencyDisplay.Style.AUTO, false, Map.of()).icons());
    }

    @Test
    void explicitStyleWins() {
        assertTrue(CurrencyDisplay.resolve(CurrencyDisplay.Style.ICONS, false, Map.of()).icons());
        assertFalse(CurrencyDisplay.resolve(CurrencyDisplay.Style.NAMES, true, Map.of()).icons());
    }

    @Test
    void styleParsingFallsBackToAuto() {
        assertEquals(CurrencyDisplay.Style.NAMES, CurrencyDisplay.Style.parse(" Names "));
        assertEquals(CurrencyDisplay.Style.ICONS, CurrencyDisplay.Style.parse("icons"));
        assertEquals(CurrencyDisplay.Style.AUTO, CurrencyDisplay.Style.parse("something"));
        assertEquals(CurrencyDisplay.Style.AUTO, CurrencyDisplay.Style.parse(null));
    }

    @Test
    void operatorCurrencyNamesAreLookedUpCaseInsensitively() {
        CurrencyDisplay display = new CurrencyDisplay(false, Map.of("money", "Dollars", "empty", " "));

        assertEquals("Dollars", display.nameOf("MONEY"));
        assertNull(display.nameOf("coins"));
        assertNull(display.nameOf("empty"));
    }

    @Test
    void editionGateWinsOverTheConfigSwitch() {
        assertEquals(VoteFeatures.State.PREMIUM_ONLY, VoteFeatures.state(false, true));
        assertEquals(VoteFeatures.State.PREMIUM_ONLY, VoteFeatures.state(false, false));
        assertEquals(VoteFeatures.State.OFF, VoteFeatures.state(true, false));
        assertEquals(VoteFeatures.State.ON, VoteFeatures.state(true, true));
    }

    @Test
    void titleTimingsAreTicks() {
        assertEquals(Duration.ofMillis(500), VoteEffectsConfig.ticks(10));
        assertEquals(Duration.ofSeconds(2), VoteEffectsConfig.ticks(40));
        assertEquals(Duration.ZERO, VoteEffectsConfig.ticks(-5));
    }

    @Test
    void rewardsFileIsNeverMerged() {
        assertTrue(ConfigMigrator.isContentFile("rewards.yml"));
        assertFalse(ConfigMigrator.isContentFile("config.yml"));
        assertFalse(ConfigMigrator.isContentFile("sites.yml"));
    }
}
