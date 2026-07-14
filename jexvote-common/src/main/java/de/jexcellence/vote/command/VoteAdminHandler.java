package de.jexcellence.vote.command;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.VoteEdition;
import de.jexcellence.vote.command.help.HelpRenderer;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.model.Vote;
import de.jexcellence.vote.service.MultiplierService;
import de.jexcellence.vote.service.VoteService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                Map.entry("jexvote.fakevote", this::onFakeVote),
                Map.entry("jexvote.key", this::onKey)
        );
    }

    private void onHelp(@NotNull CommandContext ctx) {
        List<HelpRenderer.Entry> entries = List.of(
                HelpRenderer.Entry.of("/jexvote info", "", "Show edition, version, site count and Votifier port",
                        HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/jexvote reload", "", "Reload config.yml, rewards.yml and sites.yml",
                        HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/jexvote reset", "<player>", "Reset a player's vote stats",
                        HelpRenderer.Action.SUGGEST),
                HelpRenderer.Entry.of("/jexvote resetmonthly", "", "Reset monthly vote counts for everyone",
                        HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/jexvote fakevote", "<player> [service]", "Simulate a vote for testing",
                        HelpRenderer.Action.SUGGEST),
                HelpRenderer.Entry.of("/jexvote key", "", "Show the Votifier public key, PEM, port and token",
                        HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/jexvote help", "", "Show this help",
                        HelpRenderer.Action.RUN)
        );
        new HelpRenderer("vote_admin").render(ctx.sender(), entries);
    }

    @SuppressWarnings("deprecation")
    private void onInfo(@NotNull CommandContext ctx) {
        String version = plugin.getDescription().getVersion();
        String editionName = edition instanceof VoteEdition.PremiumEdition ? "Premium" : "Free";
        var sender = ctx.sender();

        sender.sendMessage(MM.deserialize(
                "<dark_gray>━━━━ <gradient:#fde047:#f59e0b>JExVote</gradient> <dark_gray>━━━━"));
        sender.sendMessage(MM.deserialize(
                "  <gray>Edition:</gray> <gradient:#86efac:#16a34a>" + editionName + "</gradient>"));
        sender.sendMessage(MM.deserialize(
                "  <gray>Version:</gray> <white>" + version + C_WHITE));
        sender.sendMessage(MM.deserialize(
                "  <gray>Vote sites:</gray> <white>" + voteService.getVoteSites().size() + C_WHITE));
        sender.sendMessage(MM.deserialize(
                "  <gray>Votifier port:</gray> <white>" + voteConfig.getServerPort() + C_WHITE));
    }

    /**
     * Prints the Votifier connection details a vote site needs: port, v2 token, and the
     * RSA public key in both one-line (X.509 base64) and PEM form. The key is read from
     * {@code rsa/public.key}, generated on first server start.
     */
    private void onKey(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        Path keyFile = plugin.getDataFolder().toPath().resolve("rsa/public.key");
        String raw;
        try {
            raw = Files.readString(keyFile).replaceAll("\\s+", "");
        } catch (IOException e) {
            sender.sendMessage(MM.deserialize(
                    "<red>Could not read the public key — the Votifier server must start at least once to generate it."));
            return;
        }

        String token = voteConfig.getServerToken();
        sender.sendMessage(MM.deserialize(
                "<dark_gray>━━━━ <gradient:#fde047:#f59e0b>Votifier Setup</gradient> <dark_gray>━━━━"));
        sender.sendMessage(MM.deserialize(
                "  <gray>Port:</gray> <white>" + voteConfig.getServerPort() + C_WHITE));
        sender.sendMessage(MM.deserialize(
                "  <gray>v2 token:</gray> <white>" + (token.isEmpty() ? "(none)" : token) + C_WHITE));
        sender.sendMessage(MM.deserialize(
                "  <gray>Public key (one line — most sites): "
                        + "<click:copy_to_clipboard:'" + raw + "'><hover:show_text:'Click to copy'><green>[copy]</green></hover></click>"));
        sender.sendMessage(MM.deserialize("<white>" + raw + C_WHITE));
        sender.sendMessage(MM.deserialize(
                "  <gray>Public key (PEM — if the site wants BEGIN/END headers):"));
        sender.sendMessage(MM.deserialize("<white>" + toPem(raw).replace("\n", "<newline>") + C_WHITE));
        sender.sendMessage(MM.deserialize(
                "  <dark_gray>Paste one of these into the site's Votifier public-key field."));
    }

    /** Wraps a base64 X.509 key in PEM armor with 64-char lines. */
    private static @NotNull String toPem(@NotNull String base64) {
        StringBuilder sb = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int offset = 0; offset < base64.length(); offset += 64) {
            sb.append(base64, offset, Math.min(offset + 64, base64.length())).append('\n');
        }
        return sb.append("-----END PUBLIC KEY-----").toString();
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
