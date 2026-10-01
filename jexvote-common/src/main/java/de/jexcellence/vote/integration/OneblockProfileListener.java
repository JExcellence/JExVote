package de.jexcellence.vote.integration;

import de.jexcellence.oneblock.api.ActiveProfileChangedEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Forwards JExOneblock profile switches to JExVote. Only loaded when JExOneblock is installed
 * ({@link ProfileEvents#register}).
 *
 * @author JExcellence
 * @since 3.2.10
 */
final class OneblockProfileListener implements Listener {

    private final Consumer<Player> onChange;

    OneblockProfileListener(@NotNull Consumer<Player> onChange) {
        this.onChange = onChange;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProfileChanged(@NotNull ActiveProfileChangedEvent event) {
        onChange.accept(event.getPlayer());
    }
}
