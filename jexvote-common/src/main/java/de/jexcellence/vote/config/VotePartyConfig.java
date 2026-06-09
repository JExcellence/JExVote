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
 * Configuration for Vote Party animations, sounds, and title messages.
 * Allows full customization of the party completion experience.
 *
 * @author JExcellence
 */
public final class VotePartyConfig {

    private static final String REWARDS_FILE = "rewards.yml";

    /**
     * Animation settings for the Vote Party slot-machine reveal.
     */
    public record AnimationSettings(
            int spinFrames,
            long frameTicks,
            int guaranteedPicks,
            int maxTotalPicks,
            double extraStartChance,
            double extraStep
    ) {
        public static final AnimationSettings DEFAULTS = new AnimationSettings(
                14, 3L, 3, 10, 0.75, 0.08
        );
    }

    /**
     * Sound settings for Vote Party animations.
     */
    public record SoundSettings(
            @NotNull String spinSound,
            float spinVolume,
            float spinPitchBase,
            float spinPitchStep,
            @NotNull String revealSound,
            float revealVolume,
            float revealPitch
    ) {
        public static final SoundSettings DEFAULTS = new SoundSettings(
                "BLOCK_NOTE_BLOCK_HAT", 0.7f, 1.0f, 0.1f,
                "UI_TOAST_CHALLENGE_COMPLETE", 1.0f, 1.0f
        );
    }

    /**
     * Title settings for Vote Party animations.
     */
    public record TitleSettings(
            @NotNull String spinTitle,
            @NotNull String revealTitle,
            @NotNull String revealSubtitle,
            @NotNull Duration fadeIn,
            @NotNull Duration stay,
            @NotNull Duration fadeOut
    ) {
        public static final TitleSettings DEFAULTS = new TitleSettings(
                "<gradient:#fde047:#f59e0b><bold>★ VOTE PARTY ★</bold></gradient>",
                "<gradient:#86efac:#16a34a><bold>✦ REWARDS! ✦</bold></gradient>",
                "<gray>You won <gradient:#fde047:#f59e0b>{count}</gradient> rewards!</gray>",
                Duration.ofMillis(0),
                Duration.ofMillis(200),
                Duration.ofMillis(0)
        );
    }

    private final JavaPlugin plugin;
    private final Logger logger;

    private AnimationSettings animationSettings = AnimationSettings.DEFAULTS;
    private SoundSettings soundSettings = SoundSettings.DEFAULTS;
    private TitleSettings titleSettings = TitleSettings.DEFAULTS;

    public VotePartyConfig(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void load() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new java.io.File(plugin.getDataFolder(), REWARDS_FILE));

        loadAnimationSettings(config.getConfigurationSection("vote-party.animation"));
        loadSoundSettings(config.getConfigurationSection("vote-party.sounds"));
        loadTitleSettings(config.getConfigurationSection("vote-party.titles"));
    }

    private void loadAnimationSettings(@NotNull ConfigurationSection section) {
        if (section == null) {
            animationSettings = AnimationSettings.DEFAULTS;
            return;
        }

        int spinFrames = section.getInt("spin-frames", AnimationSettings.DEFAULTS.spinFrames());
        long frameTicks = section.getLong("frame-ticks", AnimationSettings.DEFAULTS.frameTicks());
        int guaranteedPicks = section.getInt("guaranteed-picks", AnimationSettings.DEFAULTS.guaranteedPicks());
        int maxTotalPicks = section.getInt("max-total-picks", AnimationSettings.DEFAULTS.maxTotalPicks());
        double extraStartChance = section.getDouble("extra-start-chance", AnimationSettings.DEFAULTS.extraStartChance());
        double extraStep = section.getDouble("extra-step", AnimationSettings.DEFAULTS.extraStep());

        // Validate values
        if (spinFrames < 1) spinFrames = AnimationSettings.DEFAULTS.spinFrames();
        if (frameTicks < 1) frameTicks = AnimationSettings.DEFAULTS.frameTicks();
        if (guaranteedPicks < 1) guaranteedPicks = AnimationSettings.DEFAULTS.guaranteedPicks();
        if (maxTotalPicks < guaranteedPicks) maxTotalPicks = AnimationSettings.DEFAULTS.maxTotalPicks();
        if (extraStartChance < 0.0 || extraStartChance > 1.0) extraStartChance = AnimationSettings.DEFAULTS.extraStartChance();
        if (extraStep < 0.0) extraStep = AnimationSettings.DEFAULTS.extraStep();

        animationSettings = new AnimationSettings(spinFrames, frameTicks, guaranteedPicks, maxTotalPicks, extraStartChance, extraStep);
    }

    private void loadSoundSettings(@NotNull ConfigurationSection section) {
        if (section == null) {
            soundSettings = SoundSettings.DEFAULTS;
            return;
        }

        String spinSound = section.getString("spin", SoundSettings.DEFAULTS.spinSound());
        String revealSound = section.getString("reveal", SoundSettings.DEFAULTS.revealSound());

        // Validate sounds exist
        if (!isValidSound(spinSound)) {
            final String invalidSpin = spinSound;
            logger.log(Level.WARNING, () -> "Invalid spin sound '" + invalidSpin + "', using default");
            spinSound = SoundSettings.DEFAULTS.spinSound();
        }
        if (!isValidSound(revealSound)) {
            final String invalidReveal = revealSound;
            logger.log(Level.WARNING, () -> "Invalid reveal sound '" + invalidReveal + "', using default");
            revealSound = SoundSettings.DEFAULTS.revealSound();
        }

        soundSettings = new SoundSettings(
                spinSound,
                (float) section.getDouble("spin-volume", SoundSettings.DEFAULTS.spinVolume()),
                (float) section.getDouble("spin-pitch-base", SoundSettings.DEFAULTS.spinPitchBase()),
                (float) section.getDouble("spin-pitch-step", SoundSettings.DEFAULTS.spinPitchStep()),
                revealSound,
                (float) section.getDouble("reveal-volume", SoundSettings.DEFAULTS.revealVolume()),
                (float) section.getDouble("reveal-pitch", SoundSettings.DEFAULTS.revealPitch())
        );
    }

    private void loadTitleSettings(@NotNull ConfigurationSection section) {
        if (section == null) {
            titleSettings = TitleSettings.DEFAULTS;
            return;
        }

        titleSettings = new TitleSettings(
                section.getString("spin", TitleSettings.DEFAULTS.spinTitle()),
                section.getString("reveal", TitleSettings.DEFAULTS.revealTitle()),
                section.getString("reveal-subtitle", TitleSettings.DEFAULTS.revealSubtitle()),
                Duration.ofMillis(section.getLong("fade-in", TitleSettings.DEFAULTS.fadeIn().toMillis())),
                Duration.ofMillis(section.getLong("stay", TitleSettings.DEFAULTS.stay().toMillis())),
                Duration.ofMillis(section.getLong("fade-out", TitleSettings.DEFAULTS.fadeOut().toMillis()))
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

    public @NotNull AnimationSettings getAnimationSettings() {
        return animationSettings;
    }

    public @NotNull SoundSettings getSoundSettings() {
        return soundSettings;
    }

    public @NotNull TitleSettings getTitleSettings() {
        return titleSettings;
    }
}
