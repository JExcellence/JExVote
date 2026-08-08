package de.jexcellence.vote.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigratorTest {

    @Test
    void deletedDefaultSiteIsNotRestored() throws InvalidConfigurationException {
        YamlConfiguration user = yaml("""
                sites:
                  custom:
                    display-name: Custom
                    vote-url: https://example.com/vote
                """);
        YamlConfiguration defaults = siteDefaults();

        int added = ConfigMigrator.mergeMissingKeys(user, defaults, 2);
        user.setDefaults(defaults);

        assertEquals(0, added);
        assertFalse(user.contains("sites.serverliste", true));
        ConfigurationSection sites = user.getConfigurationSection("sites");
        assertNotNull(sites);
        assertEquals(Set.of("custom"), sites.getKeys(false));
    }

    @Test
    void retainedSiteReceivesNewDefaultFields() throws InvalidConfigurationException {
        YamlConfiguration user = yaml("""
                sites:
                  serverliste:
                    display-name: My Serverliste Name
                """);
        YamlConfiguration defaults = siteDefaults();

        int added = ConfigMigrator.mergeMissingKeys(user, defaults, 2);

        assertEquals(2, added);
        assertEquals("My Serverliste Name", user.getString("sites.serverliste.display-name"));
        assertEquals("https://serverliste.example/vote", user.getString("sites.serverliste.vote-url"));
        assertEquals(1, user.getInt("sites.serverliste.points-per-vote"));
    }

    @Test
    void normalConfigsStillMergeWithinExistingTopLevelSection() throws InvalidConfigurationException {
        YamlConfiguration user = yaml("""
                rewards:
                  coins: 10
                """);
        YamlConfiguration defaults = yaml("""
                rewards:
                  coins: 5
                  crystals: 2
                removed-section:
                  enabled: true
                """);

        int added = ConfigMigrator.mergeMissingKeys(user, defaults, 1);

        assertEquals(1, added);
        assertEquals(10, user.getInt("rewards.coins"));
        assertEquals(2, user.getInt("rewards.crystals"));
        assertFalse(user.contains("removed-section", true));
    }

    @Test
    void emptySitesSectionDoesNotRestoreBundledSites() throws InvalidConfigurationException {
        YamlConfiguration user = yaml("sites: {}\n");

        assertEquals(0, ConfigMigrator.mergeMissingKeys(user, siteDefaults(), 2));
        assertTrue(user.getConfigurationSection("sites").getKeys(false).isEmpty());
    }

    private static YamlConfiguration siteDefaults() throws InvalidConfigurationException {
        return yaml("""
                sites:
                  serverliste:
                    display-name: Serverliste
                    vote-url: https://serverliste.example/vote
                    points-per-vote: 1
                """);
    }

    private static YamlConfiguration yaml(String content) throws InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(content);
        return configuration;
    }
}
