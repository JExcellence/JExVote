package de.jexcellence.vote.command;

import com.raindropcentral.commands.v2.CommandMessages;
import de.jexcellence.jextranslate.R18nManager;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.logging.Level;

/**
 * Routes JExCommand's built-in messages (usage, parse errors, missing permission) through JExVote's
 * translation files, with the plugin prefix.
 *
 * @author JExcellence
 */
public final class R18nCommandMessages implements CommandMessages {

    @Override
    public void send(
            @NotNull CommandSender sender,
            @NotNull String key,
            @NotNull Map<String, String> placeholders
    ) {
        if ("jexcommand.error.internal".equals(key)) {
            String detail = placeholders.getOrDefault("message", "<no message>");
            String senderName = sender.getName();
            Bukkit.getLogger().log(Level.WARNING,
                    () -> "[JExCommand] handler raised an exception: " + detail + " (sender=" + senderName + ")");
        }
        var builder = R18nManager.getInstance().msg(key).prefix();
        for (var entry : placeholders.entrySet()) {
            builder = builder.with(entry.getKey(), entry.getValue());
        }
        builder.send(sender);
    }
}
