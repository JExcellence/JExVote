package de.jexcellence.vote.api.event;

import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.api.reward.VoteRewardDescriptor;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fired just before a vote's bonus rewards are granted, letting a listener append extra
 * platform-free {@link VoteRewardDescriptor}s for this one vote - a lightweight per-vote
 * complement to the registered {@link de.jexcellence.vote.api.reward.VoteRewardProvider} SPI.
 *
 * <p>The added rewards ride the same safe path as SPI rewards: granted now if the voter is
 * online, or queued and delivered when they next join if offline.
 *
 * <p><b>Asynchronous event.</b> Handlers run off the main thread; they must be thread-safe
 * and must not call the Bukkit API - only read the context and add to
 * {@link #getAdditionalRewards()}. (This is why it is a descriptor list, not live items.)
 *
 * @author JExcellence
 * @since 1.0.0
 */
public class VotePreRewardEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final UUID playerUuid;
    private final String playerName;
    private final String serviceName;
    private final VoteSnapshot snapshot;
    private final boolean online;
    private final List<VoteRewardDescriptor> additionalRewards = new ArrayList<>();

    public VotePreRewardEvent(@NotNull UUID playerUuid, @NotNull String playerName,
                              @NotNull String serviceName, @NotNull VoteSnapshot snapshot,
                              boolean online) {
        super(true);
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.serviceName = serviceName;
        this.snapshot = snapshot;
        this.online = online;
    }

    public @NotNull UUID getPlayerUuid() {
        return playerUuid;
    }

    public @NotNull String getPlayerName() {
        return playerName;
    }

    public @NotNull String getServiceName() {
        return serviceName;
    }

    /** The voter's stats after this vote applied. */
    public @NotNull VoteSnapshot getSnapshot() {
        return snapshot;
    }

    /** Whether the voter is online on this backend right now. */
    public boolean isOnline() {
        return online;
    }

    /** The mutable list a listener adds extra rewards to. */
    public @NotNull List<VoteRewardDescriptor> getAdditionalRewards() {
        return additionalRewards;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
