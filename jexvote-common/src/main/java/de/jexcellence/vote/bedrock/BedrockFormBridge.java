package de.jexcellence.vote.bedrock;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Soft bridge to the Floodgate API - detects Bedrock players and sends
 * Cumulus forms without a hard compile dependency. When Floodgate is not
 * installed the bridge degrades silently: {@link #isBedrockPlayer} always
 * returns {@code false} and {@link #sendForm} is a no-op.
 *
 * @author JExcellence
 * @since 3.3.0
 */
public final class BedrockFormBridge {

    private static final Logger LOGGER = Logger.getLogger(BedrockFormBridge.class.getName());

    private final boolean available;
    private Object floodgateApi;

    public BedrockFormBridge() {
        boolean ok = false;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            floodgateApi = apiClass.getMethod("getInstance").invoke(null);
            ok = floodgateApi != null;
            if (ok) {
                LOGGER.log(Level.INFO, "[vote] Floodgate detected - Bedrock forms enabled");
            }
        } catch (ClassNotFoundException ignored) {
            // Floodgate not installed
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, () -> "[vote] Floodgate probe failed: " + e.getMessage());
        }
        this.available = ok;
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isBedrockPlayer(@NotNull Player player) {
        if (!available) {
            return false;
        }
        try {
            Object result = floodgateApi.getClass()
                    .getMethod("isFloodgatePlayer", UUID.class)
                    .invoke(floodgateApi, player.getUniqueId());
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sends a Cumulus form to a Bedrock player. The {@code form} must be an
     * instance of {@code org.geysermc.cumulus.form.Form}.
     */
    public void sendForm(@NotNull Player player, @NotNull Object form) {
        if (!available) {
            return;
        }
        try {
            floodgateApi.getClass()
                    .getMethod("sendForm", UUID.class, form.getClass().getInterfaces()[0])
                    .invoke(floodgateApi, player.getUniqueId(), form);
        } catch (NoSuchMethodException e) {
            try {
                Class<?> formClass = Class.forName("org.geysermc.cumulus.form.Form");
                floodgateApi.getClass()
                        .getMethod("sendForm", UUID.class, formClass)
                        .invoke(floodgateApi, player.getUniqueId(), form);
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, () -> "[vote] Failed to send Bedrock form: " + ex.getMessage());
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, () -> "[vote] Failed to send Bedrock form: " + e.getMessage());
        }
    }
}
