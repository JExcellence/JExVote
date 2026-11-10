package de.jexcellence.vote.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BundledSitesOrderTest {

    @Test
    void bundledSitesKeepTheMythblockOrder() throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("sites.yml")) {
            assertNotNull(in);
            config.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        ConfigurationSection sites = config.getConfigurationSection("sites");
        assertNotNull(sites);

        assertEquals(List.of(
                "minecraft-server", "findmcserver", "mcstat", "minecraft-servers", "minecraft-server-list",
                "minecraft-servers-me", "minecraft-pocket-servers", "topg", "minecraft-mp", "minecraft-serverlist",
                "serverliste"), List.copyOf(sites.getKeys(false)));
    }
}
