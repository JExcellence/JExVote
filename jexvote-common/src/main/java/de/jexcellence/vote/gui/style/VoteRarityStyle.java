package de.jexcellence.vote.gui.style;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

/**
 * JExVote rarity vocabulary — each tier carries a distinct glyph (colorblind
 * support) and a MiniMessage color/gradient. Shared by every view that surfaces
 * loot rarity (Vote Jackpot outcomes, streak rewards, achievement-style
 * payouts) so rarity always reads the same way across the plugin.
 *
 * <p>Deliberately decoupled from any gameplay enum: callers map their own
 * rarity bucket via {@link #byChance(double)} (for Jackpot odds) or
 * {@link #byName(String)} so this style layer carries no gameplay dependency.</p>
 *
 * <p>Mirrors the Mythblock {@code RarityStyle} 6-tier model exactly so a
 * player sees the same glyph + color across both plugins.</p>
 *
 * @author JExcellence
 * @since 3.2.0
 */
public enum VoteRarityStyle {

    /** Bottom tier — solid dark gray. */
    JUNK("·", "<dark_gray>", "", false),
    /** Baseline — gray-to-white sheen. */
    COMMON("◦", "<gradient:#9CA3AF:#FFFFFF>", "</gradient>", false),
    /** Notable — aqua-to-blue. */
    RARE("◆", "<gradient:#22D3EE:#3B82F6>", "</gradient>", false),
    /** High-value — gold-to-amber shimmer. */
    JACKPOT("★", "<gradient:#FCD34D:#F59E0B>", "</gradient>", false),
    /** Top tier — pink-to-violet. */
    DIVINE("❖", "<gradient:#F472B6:#A855F7>", "</gradient>", false),
    /** Hidden tier — obfuscated rainbow (mystery prizes). */
    SECRET("⁉", "<rainbow>", "</rainbow>", true);

    private final String glyph;
    private final String open;
    private final String close;
    private final boolean obfuscated;

    VoteRarityStyle(@NotNull String glyph, @NotNull String open, @NotNull String close, boolean obfuscated) {
        this.glyph = glyph;
        this.open = open;
        this.close = close;
        this.obfuscated = obfuscated;
    }

    /** @return the rarity glyph (e.g. {@code ★}). */
    public @NotNull String glyph() {
        return glyph;
    }

    /**
     * Colors a piece of text in this rarity's color/gradient (no glyph, no
     * obfuscation).
     */
    public @NotNull String colorize(@NotNull String text) {
        return open + text + close;
    }

    /**
     * Renders a glyph-prefixed, colored rarity label — e.g. {@code ★ JACKPOT}.
     * For {@link #SECRET} the label (not the glyph) is obfuscated.
     */
    public @NotNull String display(@NotNull String label) {
        String body = obfuscated ? "<obfuscated>" + label + "</obfuscated>" : label;
        return open + glyph + " " + body + close;
    }

    /** Item-ready (non-italic) component variant of {@link #display(String)}. */
    public @NotNull Component component(@NotNull String label) {
        return VoteStyle.line(display(label));
    }

    /**
     * Maps a jackpot drop chance to a rarity tier.
     *
     * <p>Buckets:
     * <ul>
     *   <li>≥30%: {@link #JUNK}</li>
     *   <li>15–30%: {@link #COMMON}</li>
     *   <li>5–15%: {@link #RARE}</li>
     *   <li>2–5%: {@link #JACKPOT}</li>
     *   <li>&lt;2%: {@link #DIVINE}</li>
     * </ul>
     * Callers wanting Secret behavior must opt in via {@link #SECRET} directly.
     *
     * @param chance drop chance in {@code [0, 1]} (e.g. {@code 0.04} for 4%)
     */
    public static @NotNull VoteRarityStyle byChance(double chance) {
        if (chance >= 0.30) return JUNK;
        if (chance >= 0.15) return COMMON;
        if (chance >= 0.05) return RARE;
        if (chance >= 0.02) return JACKPOT;
        return DIVINE;
    }

    /**
     * Resolves a rarity by enum name, case-insensitively, defaulting to
     * {@link #COMMON} for unknown values so callers never throw on bad data.
     */
    public static @NotNull VoteRarityStyle byName(@NotNull String name) {
        for (VoteRarityStyle style : values()) {
            if (style.name().equalsIgnoreCase(name)) {
                return style;
            }
        }
        return COMMON;
    }
}
