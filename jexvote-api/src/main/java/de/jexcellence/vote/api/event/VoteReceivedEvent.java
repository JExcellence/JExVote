package de.jexcellence.vote.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class VoteReceivedEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final String playerName;
    private final UUID playerUuid;
    private final String serviceName;
    private final String address;
    private boolean cancelled;

    public VoteReceivedEvent(@NotNull String playerName, @Nullable UUID playerUuid,
                             @NotNull String serviceName, @NotNull String address) {
        super(false);
        this.playerName = playerName;
        this.playerUuid = playerUuid;
        this.serviceName = serviceName;
        this.address = address;
    }

    public @NotNull String getPlayerName() {
        return playerName;
    }

    public @Nullable UUID getPlayerUuid() {
        return playerUuid;
    }

    public @NotNull String getServiceName() {
        return serviceName;
    }

    public @NotNull String getAddress() {
        return address;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
