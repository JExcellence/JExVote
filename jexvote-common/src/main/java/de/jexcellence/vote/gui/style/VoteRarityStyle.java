package de.jexcellence.vote.gui.style;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Rarity buckets for prize catalogs (Lucky Vote, vote party pool). A prize's bucket follows from its drop
 * chance, so the label always matches the real odds. Labels and colours live in the translation keys
 * {@code gui.common.rarity.<bucket>}; this enum only owns the thresholds.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public enum VoteRarityStyle {

    /** 15% or more. */
    COMMON(15.0),
    /** 5% up to 15%. */
    UNCOMMON(5.0),
    /** 1% up to 5%. */
    RARE(1.0),
    /** 0.1% up to 1%. */
    EPIC(0.1),
    /** Below 0.1%. */
    LEGENDARY(0.0);

    private static final String KEY_PREFIX = "gui.common.rarity.";

    private final double minimumPercent;

    VoteRarityStyle(double minimumPercent) {
        this.minimumPercent = minimumPercent;
    }

    /** @return the translation key of the coloured rarity label. */
    public @NotNull String key() {
        return KEY_PREFIX + name().toLowerCase(Locale.ROOT);
    }

    /**
     * Maps a drop chance to its bucket.
     *
     * @param percent drop chance scaled to 0-100 (e.g. {@code 4.0} for 4%)
     * @return the matching rarity bucket
     */
    public static @NotNull VoteRarityStyle byPercent(double percent) {
        for (VoteRarityStyle style : values()) {
            if (percent >= style.minimumPercent) {
                return style;
            }
        }
        return LEGENDARY;
    }
}
