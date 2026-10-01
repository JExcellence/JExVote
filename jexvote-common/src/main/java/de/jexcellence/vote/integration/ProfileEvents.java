package de.jexcellence.vote.integration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Soft hook for JExOneblock profile switches: calls back with the player after the active profile changed, so
 * vote rewards held for the Normal profile are handed over the moment it is active. Does nothing without
 * JExOneblock or on a JExOneblock without the profile event.
 *
 * @author JExcellence
 * @since 3.2.10
 */
public final class ProfileEvents {

    private static final Logger LOGGER = Logger.getLogger(ProfileEvents.class.getName());
    private static final String PLUGIN = "JExOneblock";

    private ProfileEvents() {
    }

    /**
     * Registers the profile switch callback.
     *
     * @param plugin   the owning plugin
     * @param onChange called on the player's thread after the active profile changed
     * @return {@code true} when the hook is active
     */
    public static boolean register(@NotNull JavaPlugin plugin, @NotNull Consumer<Player> onChange) {
        if (Bukkit.getPluginManager().getPlugin(PLUGIN) == null) {
            return false;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new OneblockProfileListener(onChange), plugin);
            return true;
        } catch (LinkageError ex) {
            LOGGER.log(Level.FINE, () -> "JExOneblock profile event not available: " + ex.getMessage());
            return false;
        }
    }
}
