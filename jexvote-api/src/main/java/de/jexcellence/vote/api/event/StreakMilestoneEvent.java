package de.jexcellence.vote.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Fired (on the main thread) when a player's vote streak reaches a configured
 * milestone day, so integrators can react without polling.
 *
 * @author JExcellence
 * @since 1.0.0
 */
public class StreakMilestoneEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final UUID playerUuid;
    private final int streak;
    private final int milestone;

    public StreakMilestoneEvent(@NotNull UUID playerUuid, int streak, int milestone) {
        this.playerUuid = playerUuid;
        this.streak = streak;
        this.milestone = milestone;
    }

    public @NotNull UUID getPlayerUuid() {
        return playerUuid;
    }

    /** The player's current streak. */
    public int getStreak() {
        return streak;
    }

    /** The milestone day that was hit (the configured streak-reward day). */
    public int getMilestone() {
        return milestone;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
