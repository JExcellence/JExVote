package de.jexcellence.vote.gui.style;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;

/**
 * Central JExVote visual-style vocabulary.
 *
 * <p>Single source of truth for dynamic (Java-built) styling — brand gradient,
 * semantic palette, glyphs and helpers. Static text styling lives in the
 * translation YAML; this class is used wherever the code assembles MiniMessage
 * at runtime (progress bars, badges, descriptors).</p>
 *
 * <p>Mirrors the Mythblock {@code MythStyle} structure so views feel consistent
 * across plugins, but uses vote's established green/gold/violet palette.</p>
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class VoteStyle {

    private VoteStyle() {
        // Utility class: no instances.
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    // ── Brand gradient (vote: light green » mid green » dark green) ──────

    /** Brand gradient start color (light green). */
    public static final String BRAND_FROM = "#86EFAC";
    /** Brand gradient mid color (mid green). */
    public static final String BRAND_MID  = "#22C55E";
    /** Brand gradient end color (dark green). */
    public static final String BRAND_TO   = "#16A34A";

    /** Opening brand-gradient tag. Pair with {@link #GRADIENT_CLOSE}. */
    public static final String BRAND_OPEN =
            "<gradient:" + BRAND_FROM + ":" + BRAND_MID + ":" + BRAND_TO + ">";
    /** Generic closing gradient tag. */
    public static final String GRADIENT_CLOSE = "</gradient>";

    // ── Per-context gradients ────────────────────────────────────────────

    /** Jackpot / lucky / leaderboard — gold-to-amber. */
    public static final String GRADIENT_JACKPOT = "<gradient:#FDE047:#F59E0B>";
    /** Vote-points / spend / shop — violet-to-purple. */
    public static final String GRADIENT_POINTS  = "<gradient:#D8B4FE:#9333EA>";
    /** Streak Freeze — cyan-to-blue. */
    public static final String GRADIENT_FREEZE  = "<gradient:#A5F3FC:#06B6D4>";
    /** Gift / community — same as brand. */
    public static final String GRADIENT_GIFT    = BRAND_OPEN;
    /** Admin chrome — red-to-crimson. */
    public static final String GRADIENT_ADMIN   = "<gradient:#FCA5A5:#DC2626>";
    /** Danger / negative — same as admin. */
    public static final String GRADIENT_DANGER  = GRADIENT_ADMIN;
    /** Info / aqua highlight (URLs, click prompts). */
    public static final String GRADIENT_INFO    = "<gradient:#A5F3FC:#06B6D4>";

    // ── Semantic single-color palette ────────────────────────────────────

    /** Primary accent (violet). */
    public static final String ACCENT = "<color:#9333EA>";
    /** Muted body text. */
    public static final String MUTED  = "<gray>";
    /** Faint / de-emphasized text and frames. */
    public static final String FAINT  = "<dark_gray>";
    /** Positive / success / unlocked. */
    public static final String OK     = "<color:#34D399>";
    /** Caution / cost / pending. */
    public static final String WARN   = "<color:#FBBF24>";
    /** Negative / locked / error. */
    public static final String BAD    = "<color:#F87171>";
    /** Value-highlight color used inside lore lines (matches Mythblock). */
    public static final String VALUE  = "<color:#C084FC>";

    // ── Glyphs (single-codepoint; no emoji to avoid font mismatch) ───────

    /** Chevron used instead of {@code --}/{@code ->} in lists and lore. */
    public static final String CHEVRON      = "»";
    /** Filled difficulty/rating star. */
    public static final String STAR_FILLED  = "★";
    /** Empty difficulty/rating star. */
    public static final String STAR_EMPTY   = "☆";
    /** Bullet for lore lines. */
    public static final String BULLET       = "•";
    /** Heavy vertical bar (lore line prefix in Mythblock style). */
    public static final String BAR          = "┃";
    /** Tree-style continuation (sub-item under a parent line). */
    public static final String BRANCH       = "┕";
    /** Diamond glyph for jackpot/rare callouts. */
    public static final String DIAMOND      = "❖";
    /** Snowflake glyph for Streak Freezes. */
    public static final String SNOWFLAKE    = "❄";
    /** 4-point star glyph for gift / community. */
    public static final String SPARK        = "✦";
    /** Triangle bullet for compact lists. */
    public static final String TRIANGLE     = "▸";
    /** Lock glyph (matches Mythblock; emoji but rendered consistently in 1.20+). */
    public static final String LOCK         = "🔒";
    /** Unlock glyph. */
    public static final String UNLOCK       = "🔓";
    /** Check mark for confirmations. */
    public static final String CHECK        = "✔";
    /** Cross mark for failures / closes. */
    public static final String CROSS        = "✘";

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Wraps text in the brand gradient (MiniMessage string).
     *
     * @param text raw text (may itself contain MiniMessage tags)
     * @return MiniMessage string in the brand gradient
     */
    public static @NotNull String brand(@NotNull String text) {
        return BRAND_OPEN + text + GRADIENT_CLOSE;
    }

    /**
     * Renders the brand-gradient text as a non-italic, item-ready component.
     *
     * @param text raw text
     * @return deserialized, italic-stripped component
     */
    public static @NotNull Component brandComponent(@NotNull String text) {
        return MM.deserialize(brand(text)).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Builds a fixed-width difficulty/rating bar of filled and empty stars,
     * wrapped in brackets — e.g. {@code (★★☆☆)} for {@code filled=2, total=4}.
     *
     * @param filled number of filled stars (clamped to {@code [0, total]})
     * @param total  total number of stars (minimum 1)
     * @return MiniMessage string of the bracketed star bar
     */
    public static @NotNull String stars(int filled, int total) {
        int slots  = Math.max(1, total);
        int active = Math.max(0, Math.min(slots, filled));
        StringBuilder sb = new StringBuilder(MUTED).append("(").append(WARN);
        for (int i = 0; i < slots; i++) {
            sb.append(i < active ? STAR_FILLED : STAR_EMPTY);
        }
        sb.append(MUTED).append(")");
        return sb.toString();
    }

    /**
     * Deserializes a MiniMessage string into a non-italic, item-ready lore
     * component.
     *
     * @param miniMessage MiniMessage source
     * @return italic-stripped component
     */
    public static @NotNull Component line(@NotNull String miniMessage) {
        return MM.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
    }
}
