package de.jexcellence.vote.config;

import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Configuration for the fly redemption service.
 * Supports multiple fly providers and customizable effects.
 *
 * @author JExcellence
 */
public final class FlyServiceConfig {

    private static final String CONFIG_FILE = "config.yml";

    /**
     * Settings for fly redemption effects.
     */
    public record FlyEffects(
            @NotNull String grantSound,
            float grantVolume,
            float grantPitch,
            @NotNull String expireSound,
            float expireVolume,
            float expirePitch
    ) {
        public static final FlyEffects DEFAULTS = new FlyEffects(
                "ENTITY_ENDER_DRAGON_FLAP", 1.0f, 1.0f,
                "ENTITY_ENDER_DRAGON_HURT", 1.0f, 1.0f
        );
    }

    /**
     * Settings for fly item display in the shop.
     */
    public record FlyDisplay(
            @NotNull String name,
            @NotNull List<String> lore
    ) {
        public static final FlyDisplay DEFAULTS = new FlyDisplay(
                "<gradient:#a5f3fc:#06b6d4>❄ Fly Time</gradient>",
                List.of(
                        "<gray>Redeem vote points for temporary flight.",
                        "<gray>Useful for building and exploring!"
                )
        );
    }

    private final JavaPlugin plugin;
    private final Logger logger;

    private boolean enabled = true;
    private int costPoints = 1;
    private int minutes = 45;
    private int eventFlyCostPoints = 5;
    private FlyEffects effects = FlyEffects.DEFAULTS;
    private FlyDisplay display = FlyDisplay.DEFAULTS;

    public FlyServiceConfig(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void load() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new java.io.File(plugin.getDataFolder(), CONFIG_FILE));

        loadFlySettings(config.getConfigurationSection("fly"));
    }

    private void loadFlySettings(@NotNull ConfigurationSection section) {
        if (section == null) {
            return;
        }

        enabled = section.getBoolean("enabled", true);
        costPoints = section.getInt("cost-points", 1);
        minutes = section.getInt("minutes", 45);
        eventFlyCostPoints = section.getInt("event-fly-cost-points", 5);

        loadEffects(section.getConfigurationSection("effects"));
        loadDisplay(section.getConfigurationSection("display"));
    }

    private void loadEffects(@NotNull ConfigurationSection section) {
        if (section == null) {
            effects = FlyEffects.DEFAULTS;
            return;
        }

        String grantSound = section.getString("grant-sound", FlyEffects.DEFAULTS.grantSound());
        String expireSound = section.getString("expire-sound", FlyEffects.DEFAULTS.expireSound());

        if (!isValidSound(grantSound)) {
            logger.log(Level.WARNING, () -> "Invalid grant sound '" + grantSound + "', using default");
            grantSound = FlyEffects.DEFAULTS.grantSound();
        }
        if (!isValidSound(expireSound)) {
            logger.log(Level.WARNING, () -> "Invalid expire sound '" + expireSound + "', using default");
            expireSound = FlyEffects.DEFAULTS.expireSound();
        }

        effects = new FlyEffects(
                grantSound,
                (float) section.getDouble("grant-volume", FlyEffects.DEFAULTS.grantVolume()),
                (float) section.getDouble("grant-pitch", FlyEffects.DEFAULTS.grantPitch()),
                expireSound,
                (float) section.getDouble("expire-volume", FlyEffects.DEFAULTS.expireVolume()),
                (float) section.getDouble("expire-pitch", FlyEffects.DEFAULTS.expirePitch())
        );
    }

    private void loadDisplay(@NotNull ConfigurationSection section) {
        if (section == null) {
            display = FlyDisplay.DEFAULTS;
            return;
        }

        display = new FlyDisplay(
                section.getString("name", FlyDisplay.DEFAULTS.name()),
                section.getStringList("lore").isEmpty() ? FlyDisplay.DEFAULTS.lore() : section.getStringList("lore")
        );
    }

    private boolean isValidSound(@NotNull String soundName) {
        try {
            Sound.valueOf(soundName);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getCostPoints() {
        return costPoints;
    }

    public int getMinutes() {
        return minutes;
    }

    public int getEventFlyCostPoints() {
        return eventFlyCostPoints;
    }

    public @NotNull FlyEffects getEffects() {
        return effects;
    }

    public @NotNull FlyDisplay getDisplay() {
        return display;
    }
}
