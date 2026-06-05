package de.jexcellence.vote.service;

import de.jexcellence.essentials.api.EssentialsProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Soft bridge to JExEssentials' timed-flight grant. JExEssentials is an
 * optional dependency: the call is guarded by a plugin-presence check and a
 * {@code Throwable} catch, so the server keeps working (the redemption is a
 * no-op returning {@code false}) when JExEssentials is absent.
 *
 * @author JExcellence
 */
public final class FlyBridge {

    private final Logger logger;

    public FlyBridge(@NotNull Logger logger) {
        this.logger = logger;
    }

    /**
     * Grants {@code seconds} of temporary flight to the player.
     *
     * @return {@code true} when the grant was delivered, {@code false} when
     *         JExEssentials is missing or the grant failed
     */
    public boolean grantFly(@NotNull Player player, long seconds) {
        try {
            if (Bukkit.getPluginManager().getPlugin("JExEssentials") == null) {
                return false;
            }
            RegisteredServiceProvider<EssentialsProvider> rsp =
                    Bukkit.getServicesManager().getRegistration(EssentialsProvider.class);
            if (rsp == null) {
                return false;
            }
            return rsp.getProvider().grantTimedFly(player.getUniqueId(), seconds);
        } catch (Throwable ex) {
            logger.log(Level.WARNING, "JExEssentials timed-fly grant failed", ex);
            return false;
        }
    }
}
