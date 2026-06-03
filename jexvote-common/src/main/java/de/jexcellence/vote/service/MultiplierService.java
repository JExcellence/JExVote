package de.jexcellence.vote.service;

import org.jetbrains.annotations.NotNull;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Computes the currently active vote multiplier from time-based rules.
 *
 * <p>The only source today is a configurable weekend window. The multiplier is
 * applied to vote points (exactly) and to top-level numeric reward amounts
 * (xp/currency). The service is purely pull-based: {@link #current()} derives the
 * factor from the current time on each call, so no scheduling is required.
 *
 * <p>The edition gate is fixed at construction; the per-config window settings are
 * mutable via {@link #reload(Settings)} so {@code /jexvote reload} takes effect.
 *
 * @author JExcellence
 */
public class MultiplierService {

    /**
     * Mutable weekend-window settings sourced from {@code config.yml}.
     *
     * @param weekendEnabled whether the weekend multiplier is enabled in config
     * @param weekendFactor  the multiplier applied during the weekend window
     * @param weekendDays    the days considered "weekend"
     * @param zone           the timezone used to evaluate the current day
     */
    public record Settings(boolean weekendEnabled, double weekendFactor,
                           @NotNull Set<DayOfWeek> weekendDays, @NotNull ZoneId zone) {
        public Settings {
            weekendDays = Set.copyOf(weekendDays);
        }
    }

    private final boolean editionAllowsWeekend;
    private final AtomicReference<Settings> settings;

    /**
     * @param editionAllowsWeekend whether the running edition permits the weekend multiplier
     * @param settings             the initial weekend-window settings
     */
    public MultiplierService(boolean editionAllowsWeekend, @NotNull Settings settings) {
        this.editionAllowsWeekend = editionAllowsWeekend;
        this.settings = new AtomicReference<>(settings);
    }

    /**
     * Refreshes the weekend-window settings (called on {@code /jexvote reload}).
     */
    public void reload(@NotNull Settings newSettings) {
        this.settings.set(newSettings);
    }

    /**
     * Returns the multiplier active right now (1.0 when no rule applies).
     */
    public double current() {
        if (!editionAllowsWeekend) {
            return 1.0;
        }
        Settings current = settings.get();
        if (!current.weekendEnabled() || current.weekendFactor() == 1.0 || current.weekendDays().isEmpty()) {
            return 1.0;
        }
        DayOfWeek today = ZonedDateTime.now(current.zone()).getDayOfWeek();
        return current.weekendDays().contains(today) ? current.weekendFactor() : 1.0;
    }

    /**
     * Returns whether a multiplier greater than 1.0 is currently active.
     */
    public boolean isActive() {
        return current() != 1.0;
    }
}
