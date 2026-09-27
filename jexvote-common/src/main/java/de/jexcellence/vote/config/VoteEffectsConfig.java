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
 * Configuration for vote receivement effects (sounds, particles, titles).
 * Allows full customization of the vote reward experience.
 *
 * @author JExcellence
 */
public final class VoteEffectsConfig {

    private static final String CONFIG_FILE = "config.yml";
    private static final String USING_DEFAULT = "', using default";

    /**
     * Settings for vote receivement effects.
     */
    public record VoteEffects(
            @NotNull String voteSound,
            float voteVolume,
            float votePitch,
            @NotNull String streakSound,
            float streakVolume,
            float streakPitch,
            @NotNull String milestoneSound,
            float milestoneVolume,
            float milestonePitch,
            @NotNull String milestoneTitle,
            @NotNull String milestoneSubtitle,
            @NotNull Duration titleFadeIn,
            @NotNull Duration titleStay,
            @NotNull Duration titleFadeOut
    ) {
        public static final VoteEffects DEFAULTS = new VoteEffects(
                "ENTITY_PLAYER_LEVELUP", 1.0f, 1.0f,
                "ENTITY_PLAYER_LEVELUP", 1.0f, 1.2f,
                "UI_TOAST_CHALLENGE_COMPLETE", 1.0f, 1.0f,
                "<gradient:#5EEAD4:#A78BFA>Vote streak</gradient>",
                "<#F1F5F9>New milestone reached</#F1F5F9>",
                ticks(10),
                ticks(40),
                ticks(10)
        );
    }

    private final JavaPlugin plugin;
    private final Logger logger;

    private VoteEffects effects = VoteEffects.DEFAULTS;

    public VoteEffectsConfig(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void load() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new java.io.File(plugin.getDataFolder(), CONFIG_FILE));

        loadVoteEffects(config.getConfigurationSection("vote-effects"));
    }

    private void loadVoteEffects(@NotNull ConfigurationSection section) {
        if (section == null) {
            effects = VoteEffects.DEFAULTS;
            return;
        }

        String voteSound = section.getString("vote.sound", VoteEffects.DEFAULTS.voteSound());
        String streakSound = section.getString("streak.sound", VoteEffects.DEFAULTS.streakSound());
        String milestoneSound = section.getString("milestone.sound", VoteEffects.DEFAULTS.milestoneSound());

        // Validate sounds
        if (!isValidSound(voteSound)) {
            final String invalidVote = voteSound;
            logger.log(Level.WARNING, () -> "Invalid vote sound '" + invalidVote + USING_DEFAULT);
            voteSound = VoteEffects.DEFAULTS.voteSound();
        }
        if (!isValidSound(streakSound)) {
            final String invalidStreak = streakSound;
            logger.log(Level.WARNING, () -> "Invalid streak sound '" + invalidStreak + USING_DEFAULT);
            streakSound = VoteEffects.DEFAULTS.streakSound();
        }
        if (!isValidSound(milestoneSound)) {
            final String invalidMilestone = milestoneSound;
            logger.log(Level.WARNING, () -> "Invalid milestone sound '" + invalidMilestone + USING_DEFAULT);
            milestoneSound = VoteEffects.DEFAULTS.milestoneSound();
        }

        effects = new VoteEffects(
                voteSound,
                (float) section.getDouble("vote.volume", VoteEffects.DEFAULTS.voteVolume()),
                (float) section.getDouble("vote.pitch", VoteEffects.DEFAULTS.votePitch()),
                streakSound,
                (float) section.getDouble("streak.volume", VoteEffects.DEFAULTS.streakVolume()),
                (float) section.getDouble("streak.pitch", VoteEffects.DEFAULTS.streakPitch()),
                milestoneSound,
                (float) section.getDouble("milestone.volume", VoteEffects.DEFAULTS.milestoneVolume()),
                (float) section.getDouble("milestone.pitch", VoteEffects.DEFAULTS.milestonePitch()),
                section.getString("milestone.title", VoteEffects.DEFAULTS.milestoneTitle()),
                section.getString("milestone.subtitle", VoteEffects.DEFAULTS.milestoneSubtitle()),
                ticks(section.getLong("milestone.fade-in", 10L)),
                ticks(section.getLong("milestone.stay", 40L)),
                ticks(section.getLong("milestone.fade-out", 10L))
        );
    }

    /**
     * @param ticks server ticks (20 per second), as the config documents
     * @return the same span as a duration; negative values count as zero
     */
    static @NotNull Duration ticks(long ticks) {
        return Duration.ofMillis(Math.max(0L, ticks) * 50L);
    }

    private boolean isValidSound(@NotNull String soundName) {
        try {
            Sound.valueOf(soundName);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public @NotNull VoteEffects getEffects() {
        return effects;
    }
}
