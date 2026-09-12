package de.jexcellence.vote.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Fired (on the main thread) when a vote party completes, listing its contributors,
 * so integrators can react without polling the party counter.
 *
 * @author JExcellence
 * @since 1.0.0
 */
public class VotePartyCompletedEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final int partyNumber;
    private final int target;
    private final List<UUID> contributors;

    public VotePartyCompletedEvent(int partyNumber, int target, @NotNull List<UUID> contributors) {
        this.partyNumber = partyNumber;
        this.target = target;
        this.contributors = List.copyOf(contributors);
    }

    /** The number of the party that just completed. */
    public int getPartyNumber() {
        return partyNumber;
    }

    /** The vote target that was reached. */
    public int getTarget() {
        return target;
    }

    /** The players who contributed to this party (unmodifiable). */
    public @NotNull List<UUID> getContributors() {
        return contributors;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
