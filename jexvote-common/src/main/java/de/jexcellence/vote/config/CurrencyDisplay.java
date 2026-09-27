package de.jexcellence.vote.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

/**
 * How currency rewards are written in menus. The coin and crystal icons come from the JExcellence resource
 * pack and only make sense on a server that runs JExEconomy with that pack; every other server gets the plain
 * name ({@code 500 Coins}). Operators can rename a currency with {@code display.currency-names}.
 *
 * @param icons whether the coin / crystal icons are used
 * @param names operator names per currency id (lower case), may be empty
 * @author JExcellence
 * @since 3.3.0
 */
public record CurrencyDisplay(boolean icons, @NotNull Map<String, String> names) {

    /** Plain names, no icons: the safe default before the config is read. */
    public static final CurrencyDisplay PLAIN = new CurrencyDisplay(false, Map.of());

    /** The {@code display.currency-style} options. */
    public enum Style {
        /** Icons when JExEconomy is installed, otherwise names. */
        AUTO,
        /** Always the icons. */
        ICONS,
        /** Always the names. */
        NAMES;

        /**
         * Parses a config value; unknown values fall back to {@link #AUTO}.
         *
         * @param raw the config value
         * @return the style
         */
        public static @NotNull Style parse(@Nullable String raw) {
            if (raw == null) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "icons" -> ICONS;
                case "names" -> NAMES;
                default -> AUTO;
            };
        }
    }

    public CurrencyDisplay {
        names = Map.copyOf(names);
    }

    /**
     * Resolves the display from the configured style.
     *
     * @param style           the configured style
     * @param jexEconomyFound whether the JExEconomy plugin is installed
     * @param names           operator currency names
     * @return the display to use
     */
    public static @NotNull CurrencyDisplay resolve(@NotNull Style style, boolean jexEconomyFound,
                                                   @NotNull Map<String, String> names) {
        boolean icons = switch (style) {
            case ICONS -> true;
            case NAMES -> false;
            default -> jexEconomyFound;
        };
        return new CurrencyDisplay(icons, names);
    }

    /**
     * @param currencyId a currency id from a reward
     * @return the operator name for it, or {@code null} when none is set
     */
    public @Nullable String nameOf(@NotNull String currencyId) {
        String name = names.get(currencyId.toLowerCase(Locale.ROOT));
        return name == null || name.isBlank() ? null : name;
    }
}
