package de.jexcellence.vote.settings;

import org.jetbrains.annotations.NotNull;

/**
 * One player's vote settings. Immutable; every change produces a new instance.
 *
 * @param reminder           how the player is told that a site is ready again
 * @param streakWarning      whether the player is warned before the streak breaks
 * @param broadcasts         whether the player sees other players' vote broadcasts
 * @param partyAnnouncements whether the player sees vote party announcements
 * @param effects            whether the player's own votes play sounds and titles
 * @param discordReminder    whether the player gets a Discord message when a site is ready again
 * @author JExcellence
 * @since 3.4.0
 */
public record VoteSettings(@NotNull ReminderMode reminder,
                           boolean streakWarning,
                           boolean broadcasts,
                           boolean partyAnnouncements,
                           boolean effects,
                           boolean discordReminder) {

    /** The settings of a player who never changed anything. */
    public static final VoteSettings DEFAULTS =
            new VoteSettings(ReminderMode.CHAT, true, true, true, true, false);

    public @NotNull VoteSettings withReminder(@NotNull ReminderMode mode) {
        return new VoteSettings(mode, streakWarning, broadcasts, partyAnnouncements, effects, discordReminder);
    }

    public @NotNull VoteSettings withStreakWarning(boolean value) {
        return new VoteSettings(reminder, value, broadcasts, partyAnnouncements, effects, discordReminder);
    }

    public @NotNull VoteSettings withBroadcasts(boolean value) {
        return new VoteSettings(reminder, streakWarning, value, partyAnnouncements, effects, discordReminder);
    }

    public @NotNull VoteSettings withPartyAnnouncements(boolean value) {
        return new VoteSettings(reminder, streakWarning, broadcasts, value, effects, discordReminder);
    }

    public @NotNull VoteSettings withEffects(boolean value) {
        return new VoteSettings(reminder, streakWarning, broadcasts, partyAnnouncements, value, discordReminder);
    }

    public @NotNull VoteSettings withDiscordReminder(boolean value) {
        return new VoteSettings(reminder, streakWarning, broadcasts, partyAnnouncements, effects, value);
    }

    /** @return whether any reminder that needs the periodic check is turned on. */
    public boolean wantsReminders() {
        return reminder != ReminderMode.DISABLED || streakWarning || discordReminder;
    }
}
