package de.jexcellence.vote.settings;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reflective bridge to the optional JExDiscord plugin: whether a Minecraft account is linked
 * ({@code DiscordLinkProvider#isLinked}) and a direct message through the running bot
 * ({@code DiscordMessageDispatcher#sendDirectMessage}). Every JExDiscord type is resolved by name, so this class
 * loads when JExDiscord is absent and every call then answers "not linked" / "not sent".
 *
 * @author JExcellence
 * @since 3.4.0
 */
public final class DiscordReminderBridge {

    private static final String PLUGIN = "JExDiscord";
    private static final String API_PACKAGE = "de.jexcellence.discord.api.";
    private static final String LINK_API = API_PACKAGE + "JExDiscordAPI";
    private static final String LINK_PROVIDER = API_PACKAGE + "DiscordLinkProvider";
    private static final String DISPATCHER = API_PACKAGE + "DiscordMessageDispatcher";
    private static final String EMBED_SPEC = DISPATCHER + "$EmbedSpec";
    private static final String GET = "get";

    private final Logger logger;
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    public DiscordReminderBridge(@NotNull Logger logger) {
        this.logger = logger;
    }

    /** @return whether JExDiscord is installed and enabled. */
    public boolean isInstalled() {
        var discord = Bukkit.getPluginManager().getPlugin(PLUGIN);
        return discord != null && discord.isEnabled();
    }

    /**
     * @param player the Minecraft account
     * @return whether the account has a confirmed Discord link; {@code false} without JExDiscord
     */
    public boolean isLinked(@NotNull UUID player) {
        if (!isInstalled()) {
            return false;
        }
        try {
            Optional<?> provider = (Optional<?>) Class.forName(LINK_API).getMethod(GET).invoke(null);
            if (provider.isEmpty()) {
                return false;
            }
            Method isLinked = Class.forName(LINK_PROVIDER).getMethod("isLinked", UUID.class);
            return Boolean.TRUE.equals(isLinked.invoke(provider.get(), player));
        } catch (Exception | LinkageError ex) {
            logFailureOnce(ex);
            return false;
        }
    }

    /**
     * Sends a direct message to the Discord account linked to {@code player}.
     *
     * @param player      the Minecraft account
     * @param title       embed title
     * @param description embed text
     * @param color       24-bit RGB colour
     * @return completes with {@code true} when the bot accepted the message
     */
    @SuppressWarnings("unchecked")
    public @NotNull CompletableFuture<Boolean> sendDirectMessage(@NotNull UUID player, @NotNull String title,
                                                                 @NotNull String description, int color) {
        if (!isInstalled()) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            Optional<?> provider = (Optional<?>) Class.forName(LINK_API).getMethod(GET).invoke(null);
            Class<?> dispatcherClass = Class.forName(DISPATCHER);
            Optional<?> dispatcher = (Optional<?>) dispatcherClass.getMethod(GET).invoke(null);
            if (provider.isEmpty() || dispatcher.isEmpty()
                    || !Boolean.TRUE.equals(dispatcherClass.getMethod("isReady").invoke(dispatcher.get()))) {
                return CompletableFuture.completedFuture(false);
            }
            Optional<?> discordId = (Optional<?>) Class.forName(LINK_PROVIDER)
                    .getMethod("discordIdOf", UUID.class).invoke(provider.get(), player);
            if (discordId.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            Class<?> specClass = Class.forName(EMBED_SPEC);
            Object spec = specClass.getMethod("of", String.class, String.class).invoke(null, title, description);
            spec = specClass.getMethod("color", int.class).invoke(spec, color);
            return (CompletableFuture<Boolean>) dispatcherClass
                    .getMethod("sendDirectMessage", String.class, specClass)
                    .invoke(dispatcher.get(), String.valueOf(discordId.get()), spec);
        } catch (Exception | LinkageError ex) {
            logFailureOnce(ex);
            return CompletableFuture.completedFuture(false);
        }
    }

    private void logFailureOnce(@NotNull Throwable ex) {
        if (failureLogged.compareAndSet(false, true)) {
            logger.log(Level.WARNING, () -> "JExDiscord is installed but its API could not be reached ("
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                    + "). Discord vote reminders stay off; further failures are not logged.");
        }
    }
}
