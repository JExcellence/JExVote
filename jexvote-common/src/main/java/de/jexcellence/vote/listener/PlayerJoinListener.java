package de.jexcellence.vote.listener;

import de.jexcellence.vote.service.VoteService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

public class PlayerJoinListener implements Listener {

    private final VoteService voteService;

    public PlayerJoinListener(@NotNull VoteService voteService) {
        this.voteService = voteService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        voteService.deliverPendingRewards(event.getPlayer());
    }
}
