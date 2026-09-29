package de.jexcellence.vote.settings;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * How a player is told that a vote site is ready again.
 *
 * @author JExcellence
 * @since 3.4.0
 */
public enum ReminderMode {

    /** No reminder. */
    DISABLED,
    /** A chat line with a click to open the vote menu. */
    CHAT,
    /** A title on screen. */
    TITLE;

    /** @return the lower-case id stored in the database and used in translation keys. */
    public @NotNull String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads a stored id.
     *
     * @param id       the stored id, may be {@code null} for a row written before the column existed
     * @param fallback the mode used when the id is missing or unknown
     * @return the matching mode
     */
    public static @NotNull ReminderMode fromId(@Nullable String id, @NotNull ReminderMode fallback) {
        if (id == null || id.isBlank()) {
            return fallback;
        }
        for (ReminderMode mode : values()) {
            if (mode.id().equalsIgnoreCase(id.trim())) {
                return mode;
            }
        }
        return fallback;
    }
}
