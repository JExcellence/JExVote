package de.jexcellence.vote.gui.style;

import de.jexcellence.jextranslate.MessageBuilder;
import de.jexcellence.jextranslate.R18nManager;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.format.TextStyle;
import java.util.Collection;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Formats numbers, chances, durations and relative times for every JExVote surface (GUI, chat, Bedrock).
 * Numbers follow the viewer's client locale; unit words come from the {@code gui.common.time.*} and
 * {@code gui.common.value.*} translation keys, so no English text is built in Java.
 *
 * @author JExcellence
 * @since 3.3.0
 */
public final class VoteFormat {

    private static final String TIME = "gui.common.time.";
    private static final String PARAM_VALUE = "value";
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 3600L;
    private static final long SECONDS_PER_DAY = 86400L;

    private VoteFormat() {
    }

    /** The locale numbers are formatted in: the viewer's client locale, or the JVM default for console. */
    public static @NotNull Locale locale(@Nullable Player viewer) {
        return viewer == null ? Locale.getDefault() : viewer.locale();
    }

    /** A whole number with grouping separators, e.g. {@code 20,000} or {@code 20.000}. */
    public static @NotNull String number(@Nullable Player viewer, long value) {
        return NumberFormat.getIntegerInstance(locale(viewer)).format(value);
    }

    /** A decimal with up to two fraction digits, trailing zeros dropped. */
    public static @NotNull String decimal(@Nullable Player viewer, double value) {
        NumberFormat format = NumberFormat.getNumberInstance(locale(viewer));
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(2);
        return format.format(value);
    }

    /** A percentage value that is already scaled to 0-100, e.g. {@code 0.05} stays {@code 0.05}. */
    public static @NotNull String percent(@Nullable Player viewer, double percent) {
        return decimal(viewer, percent);
    }

    /** A multiplier such as {@code x1.2}. */
    public static @NotNull String multiplier(@Nullable Player viewer, double factor) {
        return R18nManager.getInstance().msg("gui.common.value.multiplier")
                .with(PARAM_VALUE, decimal(viewer, factor)).text(viewer);
    }

    /** A day count with the right singular/plural word, e.g. {@code 1 day}, {@code 5 days}. */
    public static @NotNull String days(@Nullable Player viewer, int days) {
        return R18nManager.getInstance().msg("gui.common.value.days").count("count", days).text(viewer);
    }

    /** Short day names in the viewer's language, e.g. {@code Sat, Sun}. */
    public static @NotNull String dayNames(@Nullable Player viewer, @NotNull Collection<DayOfWeek> days) {
        Locale locale = locale(viewer);
        return days.stream()
                .sorted()
                .map(day -> day.getDisplayName(TextStyle.SHORT, locale))
                .collect(Collectors.joining(", "));
    }

    /** A compact duration: {@code 2 h 5 min}, {@code 45 min} or {@code 30 s}. */
    public static @NotNull String duration(@Nullable Player viewer, long seconds) {
        long safe = Math.max(0L, seconds);
        long days = safe / SECONDS_PER_DAY;
        long hours = (safe % SECONDS_PER_DAY) / SECONDS_PER_HOUR;
        long minutes = (safe % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        if (days > 0L) {
            return time("days-hours").with("days", days).with("hours", hours).text(viewer);
        }
        if (hours > 0L) {
            return time("hours-minutes").with("hours", hours).with("minutes", minutes).text(viewer);
        }
        if (minutes > 0L) {
            return time("minutes").with(PARAM_VALUE, minutes).text(viewer);
        }
        return time("seconds").with(PARAM_VALUE, safe).text(viewer);
    }

    /** How long ago something happened: {@code 3 h ago}, {@code just now}, or {@code never} for {@code null}. */
    public static @NotNull String ago(@Nullable Player viewer, @Nullable Instant at) {
        if (at == null) {
            return time("never").text(viewer);
        }
        long seconds = Duration.between(at, Instant.now()).getSeconds();
        return agoSeconds(viewer, seconds);
    }

    /** How long ago an epoch-second timestamp was. */
    public static @NotNull String agoEpoch(@Nullable Player viewer, long epochSeconds) {
        return agoSeconds(viewer, Instant.now().getEpochSecond() - epochSeconds);
    }

    private static @NotNull String agoSeconds(@Nullable Player viewer, long seconds) {
        if (seconds < SECONDS_PER_MINUTE) {
            return time("just-now").text(viewer);
        }
        return time("ago").with(PARAM_VALUE, duration(viewer, seconds)).text(viewer);
    }

    private static @NotNull MessageBuilder time(@NotNull String key) {
        return R18nManager.getInstance().msg(TIME + key);
    }
}
