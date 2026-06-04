package de.jexcellence.vote.command;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.VoteEdition;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.model.Vote;
import de.jexcellence.vote.service.MultiplierService;
import de.jexcellence.vote.service.VoteService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class VoteAdminHandler {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String C_WHITE = "</white>";
    private static final String PARAM_PLAYER = "player";

    private final JavaPlugin plugin;
    private final VoteEdition edition;
    private final VoteService voteService;
    private final VoteConfig voteConfig;
    private final VoteRewardConfig rewardConfig;

    public VoteAdminHandler(@NotNull JavaPlugin plugin,
                            @NotNull VoteEdition edition,
                            @NotNull VoteService voteService,
                            @NotNull VoteConfig voteConfig,
                            @NotNull VoteRewardConfig rewardConfig) {
        this.plugin = plugin;
        this.edition = edition;
        this.voteService = voteService;
        this.voteConfig = voteConfig;
        this.rewardConfig = rewardConfig;
    }

    public @NotNull Map<String, CommandHandler> handlerMap() {
        return Map.ofEntries(
                Map.entry("jexvote", this::onHelp),
                Map.entry("jexvote.help", this::onHelp),
                Map.entry("jexvote.info", this::onInfo),
                Map.entry("jexvote.reload", this::onReload),
                Map.entry("jexvote.reset", this::onReset),
                Map.entry("jexvote.resetmonthly", this::onResetMonthly),
                Map.entry("jexvote.fakevote", this::onFakeVote)
        );
    }

    private void onHelp(@NotNull CommandContext ctx) {
        var sender = ctx.sender();

        sender.sendMessage(MM.deserialize(
                "<dark_gray>━━━━ <gradient:#fca5a5:#dc2626><bold>JExVote Admin</bold></gradient> <dark_gray>━━━━"));

        record HelpEntry(String command, String args, String description, boolean suggest) {}

        List<HelpEntry> entries = List.of(
                new HelpEntry("/jexvote info", "", "Show edition and version", false),
                new HelpEntry("/jexvote reload", "", "Reload configuration files", false),
                new HelpEntry("/jexvote reset", "<player>", "Reset a player's vote stats", true),
                new HelpEntry("/jexvote resetmonthly", "", "Reset monthly votes for everyone", false),
                new HelpEntry("/jexvote fakevote", "<player> [service]", "Simulate a vote for testing", true),
                new HelpEntry("/jexvote help", "", "Show this help", false)
        );

        for (HelpEntry e : entries) {
            String full = e.command() + (e.args().isEmpty() ? "" : " " + e.args());
            Component line = MM.deserialize(
                    "  <dark_gray>▸</dark_gray> <gradient:#fca5a5:#dc2626>" + e.command() + "</gradient>"
                            + (e.args().isEmpty() ? "" : " <dark_gray>⟨</dark_gray><white>" + e.args() + C_WHITE + "<dark_gray>⟩</dark_gray>")
                            + " <dark_gray>—</dark_gray> <gray>" + e.description() + "</gray>");

            if (e.suggest()) {
                line = line.clickEvent(ClickEvent.suggestCommand(e.command() + " "))
                        .hoverEvent(HoverEvent.showText(MM.deserialize(
                                "<gradient:#fca5a5:#dc2626>" + full + "</gradient>\n"
                                        + "<gray>" + e.description() + "</gray>\n"
                                        + "<dark_gray>Click to suggest</dark_gray>")));
            } else {
                line = line.clickEvent(ClickEvent.runCommand(e.command()))
                        .hoverEvent(HoverEvent.showText(MM.deserialize(
                                "<gradient:#fca5a5:#dc2626>" + full + "</gradient>\n"
                                        + "<gray>" + e.description() + "</gray>\n"
                                        + "<dark_gray>Click to run</dark_gray>")));
            }

            sender.sendMessage(line);
        }
    }

    @SuppressWarnings("deprecation")
    private void onInfo(@NotNull CommandContext ctx) {
        String version = plugin.getDescription().getVersion();
        String editionName = edition instanceof VoteEdition.PremiumEdition ? "Premium" : "Free";
        var sender = ctx.sender();

        sender.sendMessage(MM.deserialize(
                "<dark_gray>━━━━ <gradient:#fde047:#f59e0b><bold>JExVote</bold></gradient> <dark_gray>━━━━"));
        sender.sendMessage(MM.deserialize(
                "  <gray>Edition:</gray> <gradient:#86efac:#16a34a>" + editionName + "</gradient>"));
        sender.sendMessage(MM.deserialize(
                "  <gray>Version:</gray> <white>" + version + C_WHITE));
        sender.sendMessage(MM.deserialize(
                "  <gray>Vote sites:</gray> <white>" + voteService.getVoteSites().size() + C_WHITE));
        sender.sendMessage(MM.deserialize(
                "  <gray>Votifier port:</gray> <white>" + voteConfig.getServerPort() + C_WHITE));
    }

    private void onReload(@NotNull CommandContext ctx) {
        voteConfig.load();
        rewardConfig.load();
        voteService.reload(
                voteConfig.getVoteSites(),
                voteConfig.getStreakTimeoutHours(),
                voteConfig.getStreakCommands(),
                voteConfig.getRecordRetentionDays(),
                voteConfig.getStreakClaimMode() == VoteConfig.StreakClaimMode.MANUAL,
                new MultiplierService.Settings(
                        voteConfig.isWeekendMultiplierEnabled(),
                        voteConfig.getWeekendMultiplierFactor(),
                        voteConfig.getWeekendMultiplierDays(),
                        voteConfig.getWeekendMultiplierTimezone()),
                voteConfig.getFreezeSettings());
        r18n().msg("vote.reload").prefix().send(ctx.sender());
    }

    private void onReset(@NotNull CommandContext ctx) {
        OfflinePlayer target = ctx.require(PARAM_PLAYER, OfflinePlayer.class);
        String name = target.getName() != null ? target.getName() : target.getUniqueId().toString();
        voteService.resetPlayer(target.getUniqueId()).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                r18n().msg("vote.reset.success").prefix().with(PARAM_PLAYER, name).send(ctx.sender());
            } else {
                ctx.sender().sendMessage(MM.deserialize(
                        "<gradient:#fca5a5:#dc2626>✘</gradient> <red>No vote data found for</red> <white>"
                                + name + C_WHITE));
            }
        });
    }

    private void onResetMonthly(@NotNull CommandContext ctx) {
        voteService.resetAllMonthlyVotes();
        r18n().msg("vote.reset.all_success").prefix().send(ctx.sender());
    }

    private void onFakeVote(@NotNull CommandContext ctx) {
        Player target = ctx.require(PARAM_PLAYER, Player.class);
        String playerName = target.getName();
        String service = ctx.get("service", String.class).orElse("TestService");

        Vote vote = new Vote(playerName, service, "127.0.0.1", Instant.now());
        voteService.processVote(vote).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                ctx.sender().sendMessage(MM.deserialize(
                        "<gradient:#86efac:#16a34a>✔</gradient> <gray>Fake vote submitted for</gray> <white>"
                                + playerName + C_WHITE + " <gray>on</gray> <white>" + service + C_WHITE));
            } else {
                ctx.sender().sendMessage(MM.deserialize(
                        "<gradient:#fca5a5:#dc2626>✘</gradient> <red>Failed to submit fake vote</red>"));
            }
        });
    }

    private static R18nManager r18n() { return R18nManager.getInstance(); }
}
