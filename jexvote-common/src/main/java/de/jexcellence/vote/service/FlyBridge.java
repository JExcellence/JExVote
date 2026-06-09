package de.jexcellence.vote.service;

import de.jexcellence.essentials.api.EssentialsProvider;
import de.jexcellence.vote.config.FlyServiceConfig;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
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
 * <p>Supports configurable sounds for grant and expiration events.
 *
 * @author JExcellence
 */
public final class FlyBridge {

    private final Logger logger;
    private final FlyServiceConfig config;

    public FlyBridge(@NotNull Logger logger, @NotNull FlyServiceConfig config) {
        this.logger = logger;
        this.config = config;
    }

    /**
     * Grants {@code seconds} of temporary flight to the player.
     * Plays the configured grant sound on success.
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
            boolean success = rsp.getProvider().grantTimedFly(player.getUniqueId(), seconds);
            if (success) {
                playGrantSound(player);
            }
            return success;
        } catch (Throwable ex) {
            logger.log(Level.WARNING, "JExEssentials timed-fly grant failed", ex);
            return false;
        }
    }

    /**
     * Plays the configured grant sound for the player.
     */
    private void playGrantSound(@NotNull Player player) {
        try {
            Sound sound = Sound.valueOf(config.getEffects().grantSound());
            player.playSound(player, sound, config.getEffects().grantVolume(), config.getEffects().grantPitch());
        } catch (Exception e) {
            // Silently ignore sound errors
        }
    }

    /**
     * Plays the configured expiration sound for the player.
     * Called when fly time expires.
     */
    public void playExpireSound(@NotNull Player player) {
        try {
            Sound sound = Sound.valueOf(config.getEffects().expireSound());
            player.playSound(player, sound, config.getEffects().expireVolume(), config.getEffects().expirePitch());
        } catch (Exception e) {
            // Silently ignore sound errors
        }
    }
}
