package de.jexcellence.vote.listener;

import de.jexcellence.jexplatform.utility.item.HeadBuilder;
import de.jexcellence.vote.service.VoteService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

/**
 * On join: remembers the player's skin for leaderboard heads and delivers votes queued while they were offline.
 *
 * @author JExcellence
 */
public class PlayerJoinListener implements Listener {

    private final VoteService voteService;

    public PlayerJoinListener(@NotNull VoteService voteService) {
        this.voteService = voteService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        HeadBuilder.cacheOnJoin(event.getPlayer());
        voteService.deliverPendingRewards(event.getPlayer());
    }
}
