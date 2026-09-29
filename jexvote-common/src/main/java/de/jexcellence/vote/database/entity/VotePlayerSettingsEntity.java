package de.jexcellence.vote.database.entity;

import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import de.jexcellence.vote.settings.ReminderMode;
import de.jexcellence.vote.settings.VoteSettings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The settings a player chose in {@code /vote settings}: one row per player, holding only the player's UUID and
 * the setting values. Every setting column is nullable; {@code null} means the player never changed that option
 * and the default applies, so later options can be added as new nullable columns without touching existing rows.
 *
 * @author JExcellence
 * @since 3.4.0
 */
@Entity
@Table(name = "jexvote_player_settings", indexes = {
        @Index(name = "idx_vote_settings_uuid", columnList = "player_uuid", unique = true),
        @Index(name = "idx_vote_settings_discord", columnList = "discord_reminder")
})
public class VotePlayerSettingsEntity extends LongIdEntity {

    @Column(name = "player_uuid", nullable = false, unique = true, length = 36)
    private UUID playerUuid;

    @Column(name = "reminder_mode", length = 16)
    private String reminderMode;

    @Column(name = "streak_warning")
    private Boolean streakWarning;

    @Column(name = "show_broadcasts")
    private Boolean showBroadcasts;

    @Column(name = "party_announcements")
    private Boolean partyAnnouncements;

    @Column(name = "vote_effects")
    private Boolean voteEffects;

    @Column(name = "discord_reminder")
    private Boolean discordReminder;

    protected VotePlayerSettingsEntity() {
    }

    public VotePlayerSettingsEntity(@NotNull UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public @NotNull UUID getPlayerUuid() {
        return playerUuid;
    }

    /** @return the stored settings with defaults for every column that is still {@code null}. */
    public @NotNull VoteSettings toSettings() {
        VoteSettings defaults = VoteSettings.DEFAULTS;
        return new VoteSettings(
                ReminderMode.fromId(reminderMode, defaults.reminder()),
                orDefault(streakWarning, defaults.streakWarning()),
                orDefault(showBroadcasts, defaults.broadcasts()),
                orDefault(partyAnnouncements, defaults.partyAnnouncements()),
                orDefault(voteEffects, defaults.effects()),
                orDefault(discordReminder, defaults.discordReminder()));
    }

    /** Writes every value of {@code settings} into this row. */
    public void apply(@NotNull VoteSettings settings) {
        this.reminderMode = settings.reminder().id();
        this.streakWarning = settings.streakWarning();
        this.showBroadcasts = settings.broadcasts();
        this.partyAnnouncements = settings.partyAnnouncements();
        this.voteEffects = settings.effects();
        this.discordReminder = settings.discordReminder();
    }

    private static boolean orDefault(@Nullable Boolean stored, boolean fallback) {
        return stored == null ? fallback : stored;
    }
}
