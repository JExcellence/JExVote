package de.jexcellence.vote.settings;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * The options of the vote settings screen, in display order. Each option has a fixed list of values (their ids
 * are translation keys under {@code vote_settings.value.}) and knows how to read and write its value on a
 * {@link VoteSettings}. Menus and Bedrock forms both work on these, so both show the same choices.
 *
 * @author JExcellence
 * @since 3.4.0
 */
public enum VoteSettingOption {

    REMINDER("reminder", List.of(Values.DISABLED, "chat", "title"),
            settings -> settings.reminder().ordinal(),
            (settings, index) -> settings.withReminder(ReminderMode.values()[index])),
    STREAK_WARNING("streak-warning", VoteSettings::streakWarning, VoteSettings::withStreakWarning),
    BROADCASTS("broadcasts", VoteSettings::broadcasts, VoteSettings::withBroadcasts),
    PARTY("party", VoteSettings::partyAnnouncements, VoteSettings::withPartyAnnouncements),
    EFFECTS("effects", VoteSettings::effects, VoteSettings::withEffects),
    DISCORD("discord", VoteSettings::discordReminder, VoteSettings::withDiscordReminder);

    private final String id;
    private final List<String> choices;
    private final ToIntFunction<VoteSettings> reader;
    private final BiFunction<VoteSettings, Integer, VoteSettings> writer;

    VoteSettingOption(@NotNull String id, @NotNull List<String> choices,
                      @NotNull ToIntFunction<VoteSettings> reader,
                      @NotNull BiFunction<VoteSettings, Integer, VoteSettings> writer) {
        this.id = id;
        this.choices = choices;
        this.reader = reader;
        this.writer = writer;
    }

    VoteSettingOption(@NotNull String id, @NotNull Function<VoteSettings, Boolean> flag,
                      @NotNull BiFunction<VoteSettings, Boolean, VoteSettings> setter) {
        this(id, List.of(Values.ENABLED, Values.DISABLED),
                settings -> Boolean.TRUE.equals(flag.apply(settings)) ? 0 : 1,
                (settings, index) -> setter.apply(settings, index == 0));
    }

    /** @return the option id, the translation key segment under {@code vote_settings.option.}. */
    public @NotNull String id() {
        return id;
    }

    /** @return the value ids in cycle order. */
    public @NotNull List<String> choices() {
        return choices;
    }

    /** @return the index of the current value in {@link #choices()}. */
    public int index(@NotNull VoteSettings settings) {
        return reader.applyAsInt(settings);
    }

    /** @return the id of the current value. */
    public @NotNull String valueId(@NotNull VoteSettings settings) {
        return choices.get(index(settings));
    }

    /** @return whether the current value is one that turns the option on. */
    public boolean isActive(@NotNull VoteSettings settings) {
        String value = valueId(settings);
        return !Values.DISABLED.equals(value);
    }

    /**
     * Sets the value at {@code index}.
     *
     * @param settings the current settings
     * @param index    the value index, wrapped into range
     * @return the changed settings
     */
    public @NotNull VoteSettings select(@NotNull VoteSettings settings, int index) {
        int size = choices.size();
        return writer.apply(settings, Math.floorMod(index, size));
    }

    /**
     * Moves to the next or previous value, wrapping around.
     *
     * @param settings the current settings
     * @param forward  {@code true} for the next value, {@code false} for the previous one
     * @return the changed settings
     */
    public @NotNull VoteSettings cycle(@NotNull VoteSettings settings, boolean forward) {
        return select(settings, index(settings) + (forward ? 1 : -1));
    }

    /** @return whether this option is a plain on/off switch. */
    public boolean isSwitch() {
        return choices.size() == 2 && Values.ENABLED.equals(choices.get(0));
    }

    /** Value ids shared by the options; a holder class because enum constructors cannot read enum statics. */
    private static final class Values {
        private static final String ENABLED = "enabled";
        private static final String DISABLED = "disabled";

        private Values() {
        }
    }
}
