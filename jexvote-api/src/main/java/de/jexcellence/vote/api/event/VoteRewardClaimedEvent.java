package de.jexcellence.vote.api.event;

import de.jexcellence.vote.api.model.VoteSnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class VoteRewardClaimedEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final UUID playerUuid;
    private final String serviceName;
    private final VoteSnapshot snapshot;

    public VoteRewardClaimedEvent(@NotNull UUID playerUuid, @NotNull String serviceName,
                                  @NotNull VoteSnapshot snapshot) {
        super(false);
        this.playerUuid = playerUuid;
        this.serviceName = serviceName;
        this.snapshot = snapshot;
    }

    public @NotNull UUID getPlayerUuid() {
        return playerUuid;
    }

    public @NotNull String getServiceName() {
        return serviceName;
    }

    public @NotNull VoteSnapshot getSnapshot() {
        return snapshot;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
