package de.jexcellence.vote.command.help;

import de.jexcellence.jextranslate.R18nManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * Renders a list of help entries (e.g. {@code /vote …}, {@code /jexvote …}) to
 * a {@link CommandSender} using a configurable i18n key prefix. Both player
 * and admin help drive through this class so styling, aliases, click/hover
 * behavior and the banner stay consistent.
 *
 * <p>Required i18n keys (where {@code <prefix>} is e.g. {@code vote_help} or
 * {@code vote_admin}):
 * <ul>
 *   <li>{@code <prefix>.banner} — header line shown once at the top.</li>
 *   <li>{@code <prefix>.entry} — one line per command. Placeholders:
 *       {@code {command}}, {@code {args}}, {@code {description}}.</li>
 *   <li>{@code <prefix>.entry-args} — wrapper used to format the args block
 *       ({@code {args}}); rendered into the {@code {args}} placeholder of
 *       {@code entry}, or empty when the command has no args.</li>
 *   <li>{@code <prefix>.hover-base} — hover tooltip header line. Placeholders:
 *       {@code {full}}, {@code {description}}.</li>
 *   <li>{@code <prefix>.hover-aliases} — alias hover line. Placeholder:
 *       {@code {aliases}}. Skipped when an entry has no aliases.</li>
 *   <li>{@code <prefix>.hover-action-run} / {@code .hover-action-suggest} —
 *       the "click to run/suggest" hint.</li>
 * </ul>
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class HelpRenderer {

    /** What clicking a help entry does. */
    public enum Action {
        /** Run the command immediately. */
        RUN,
        /** Pre-fill the command in the chat box for editing. */
        SUGGEST
    }

    /**
     * One help entry.
     *
     * @param command     the full command including the leading slash (e.g. {@code /vote shop}).
     * @param args        the argument signature (e.g. {@code <player|random>}), or empty.
     * @param description the short description shown after the dash and on hover.
     * @param aliases     any aliases for this subcommand (without the leading slash),
     *                    shown on hover; empty list to omit the alias line.
     * @param action      whether clicking runs or pre-fills the command.
     */
    public record Entry(@NotNull String command,
                        @NotNull String args,
                        @NotNull String description,
                        @NotNull List<String> aliases,
                        @NotNull Action action) {

        /** Convenience constructor: no aliases. */
        public static @NotNull Entry of(@NotNull String command, @NotNull String args,
                                        @NotNull String description, @NotNull Action action) {
            return new Entry(command, args, description, Collections.emptyList(), action);
        }

        /** Convenience constructor with aliases. */
        public static @NotNull Entry of(@NotNull String command, @NotNull String args,
                                        @NotNull String description,
                                        @NotNull List<String> aliases,
                                        @NotNull Action action) {
            return new Entry(command, args, description, aliases, action);
        }
    }

    private final String prefix;

    /**
     * @param prefix i18n key prefix (e.g. {@code "vote_help"} or {@code "vote_admin"}).
     */
    public HelpRenderer(@NotNull String prefix) {
        this.prefix = prefix;
    }

    /**
     * Renders the banner + all entries to {@code sender}.
     */
    public void render(@NotNull CommandSender sender, @NotNull List<Entry> entries) {
        R18nManager r18n = R18nManager.getInstance();
        r18n.msg(prefix + ".banner").send(sender);
        for (Entry entry : entries) {
            sender.sendMessage(renderEntry(entry));
        }
    }

    private @NotNull Component renderEntry(@NotNull Entry entry) {
        R18nManager r18n = R18nManager.getInstance();
        // The entry line embeds {args} into another MiniMessage template, so we
        // need the args block as a serialized MiniMessage fragment — not a
        // raw component (which would lose its styling on re-parse).
        String renderedArgs = entry.args().isBlank()
                ? ""
                : MiniMessage.miniMessage().serialize(
                        r18n.msg(prefix + ".entry-args")
                                .with("args", entry.args()).itemComponent(null));

        Component line = r18n.msg(prefix + ".entry")
                .with("command", entry.command())
                .with("args", renderedArgs)
                .with("description", entry.description())
                .itemComponent(null);

        String full = entry.args().isBlank()
                ? entry.command()
                : entry.command() + " " + entry.args();

        Component hover = buildHover(entry, full);
        ClickEvent click = entry.action() == Action.RUN
                ? ClickEvent.runCommand(entry.command())
                : ClickEvent.suggestCommand(entry.command() + " ");

        return line.hoverEvent(HoverEvent.showText(hover)).clickEvent(click);
    }

    private @NotNull Component buildHover(@NotNull Entry entry, @NotNull String full) {
        R18nManager r18n = R18nManager.getInstance();
        Component hover = r18n.msg(prefix + ".hover-base")
                .with("full", full)
                .with("description", entry.description())
                .itemComponent(null);

        if (!entry.aliases().isEmpty()) {
            Component aliasLine = r18n.msg(prefix + ".hover-aliases")
                    .with("aliases", String.join(", ", entry.aliases()))
                    .itemComponent(null);
            hover = hover.append(Component.newline()).append(aliasLine);
        }

        String actionKey = entry.action() == Action.RUN
                ? prefix + ".hover-action-run"
                : prefix + ".hover-action-suggest";
        hover = hover.append(Component.newline()).append(r18n.msg(actionKey).itemComponent(null));
        return hover;
    }
}
