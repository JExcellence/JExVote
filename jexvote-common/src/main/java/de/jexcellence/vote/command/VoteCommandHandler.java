package de.jexcellence.vote.command;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.vote.service.VoteLeaderboardService;
import de.jexcellence.vote.service.VoteService;
import de.jexcellence.vote.view.VoteOverviewView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public final class VoteCommandHandler {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final VoteService voteService;
    private final VoteLeaderboardService leaderboardService;
    private final VoteConfig voteConfig;
    private final VoteOverviewView overviewView;

    public VoteCommandHandler(@NotNull VoteService voteService,
                              @NotNull VoteLeaderboardService leaderboardService,
                              @NotNull VoteConfig voteConfig,
                              @NotNull VoteOverviewView overviewView) {
        this.voteService = voteService;
        this.leaderboardService = leaderboardService;
        this.voteConfig = voteConfig;
        this.overviewView = overviewView;
    }

    public @NotNull Map<String, CommandHandler> handlerMap() {
        return Map.ofEntries(
                Map.entry("vote", this::onVote),
                Map.entry("vote.help", this::onHelp),
                Map.entry("vote.sites", this::onSites),
                Map.entry("vote.stats", this::onStats),
                Map.entry("vote.top", this::onTop)
        );
    }

    private void onVote(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElseThrow();
        overviewView.open(player);
    }

    private void onHelp(@NotNull CommandContext ctx) {
        var sender = ctx.sender();

        sender.sendMessage(MM.deserialize(
                "<dark_gray>━━━━ <gradient:#86efac:#16a34a><bold>Vote Help</bold></gradient> <dark_gray>━━━━"));

        record HelpEntry(String command, String args, String description, boolean suggest) {}

        List<HelpEntry> entries = List.of(
                new HelpEntry("/vote", "", "Open the vote GUI", false),
                new HelpEntry("/vote sites", "", "List all vote sites with links", false),
                new HelpEntry("/vote stats", "[player]", "View vote statistics", true),
                new HelpEntry("/vote top", "[count]", "View the vote leaderboard", true),
                new HelpEntry("/vote help", "", "Show this help", false)
        );

        for (HelpEntry e : entries) {
            String full = e.command() + (e.args().isEmpty() ? "" : " " + e.args());
            Component line = MM.deserialize(
                    "  <dark_gray>▸</dark_gray> <gradient:#86efac:#16a34a>" + e.command() + "</gradient>"
                            + (e.args().isEmpty() ? "" : " <dark_gray>⟨</dark_gray><white>" + e.args() + "</white><dark_gray>⟩</dark_gray>")
                            + " <dark_gray>—</dark_gray> <gray>" + e.description() + "</gray>");

            if (e.suggest()) {
                line = line.clickEvent(ClickEvent.suggestCommand(e.command() + " "))
                        .hoverEvent(HoverEvent.showText(MM.deserialize(
                                "<gradient:#86efac:#16a34a>" + full + "</gradient>\n"
                                        + "<gray>" + e.description() + "</gray>\n"
                                        + "<dark_gray>Click to suggest</dark_gray>")));
            } else {
                line = line.clickEvent(ClickEvent.runCommand(e.command()))
                        .hoverEvent(HoverEvent.showText(MM.deserialize(
                                "<gradient:#86efac:#16a34a>" + full + "</gradient>\n"
                                        + "<gray>" + e.description() + "</gray>\n"
                                        + "<dark_gray>Click to run</dark_gray>")));
            }

            sender.sendMessage(line);
        }
    }

    private void onSites(@NotNull CommandContext ctx) {
        Map<String, VoteSite> sites = voteService.getVoteSites();
        if (sites.isEmpty()) {
            r18n().msg("vote.sites.no_sites").prefix().send(ctx.sender());
            return;
        }

        r18n().msg("vote.sites.header").prefix().send(ctx.sender());
        for (VoteSite site : sites.values()) {
            Component entry = Component.text(site.displayName(), NamedTextColor.GREEN)
                    .append(Component.text(" — ", NamedTextColor.DARK_GRAY));

            if (site.voteUrl() != null) {
                entry = entry.append(MM.deserialize(
                        "<click:open_url:'" + site.voteUrl() + "'><hover:show_text:'<gray>Open in browser</gray>"
                                + "<newline><aqua>" + site.voteUrl() + "</aqua>"
                                + "<newline><yellow>Click to vote!</yellow>'>"
                                + "<gradient:#a5f3fc:#06b6d4>Click to vote</gradient></hover></click>"));
            } else {
                entry = entry.append(Component.text(site.serviceName(), NamedTextColor.WHITE));
            }

            ctx.sender().sendMessage(entry);
        }
    }

    private void onStats(@NotNull CommandContext ctx) {
        OfflinePlayer target = ctx.get("player", OfflinePlayer.class)
                .orElseGet(() -> ctx.asPlayer().orElseThrow());

        voteService.getPlayerStats(target.getUniqueId()).thenAccept(stats -> {
            r18n().msg("vote.stats.header").send(ctx.sender());
            r18n().msg("vote.stats.total").prefix()
                    .with("total", String.valueOf(stats.totalVotes())).send(ctx.sender());
            r18n().msg("vote.stats.monthly").prefix()
                    .with("monthly", String.valueOf(stats.monthlyVotes())).send(ctx.sender());
            r18n().msg("vote.stats.streak").prefix()
                    .with("streak", String.valueOf(stats.currentStreak())).send(ctx.sender());
            r18n().msg("vote.stats.highest").prefix()
                    .with("highest", String.valueOf(stats.highestStreak())).send(ctx.sender());
            r18n().msg("vote.stats.points").prefix()
                    .with("points", String.valueOf(stats.votePoints())).send(ctx.sender());
        });
    }

    private void onTop(@NotNull CommandContext ctx) {
        int count = ctx.get("count", Long.class).map(Long::intValue).orElse(10);
        leaderboardService.getAllTimeTop(Math.min(count, 50)).thenAccept(top -> {
            r18n().msg("vote.leaderboard.header").send(ctx.sender());
            if (top.isEmpty()) {
                r18n().msg("vote.leaderboard.empty").prefix().send(ctx.sender());
                return;
            }
            for (int i = 0; i < top.size(); i++) {
                VoteSnapshot entry = top.get(i);
                String name = entry.playerName() != null ? entry.playerName() : "Unknown";
                r18n().msg("vote.leaderboard.entry").prefix()
                        .with("rank", String.valueOf(i + 1))
                        .with("player", name)
                        .with("votes", String.valueOf(entry.totalVotes()))
                        .send(ctx.sender());
            }
        });
    }

    private static R18nManager r18n() { return R18nManager.getInstance(); }
}
