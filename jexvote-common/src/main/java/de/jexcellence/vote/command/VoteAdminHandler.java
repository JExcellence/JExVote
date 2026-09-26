package de.jexcellence.vote.command;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.VoteEdition;
import de.jexcellence.vote.command.help.HelpRenderer;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.model.Vote;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.vote.service.MultiplierService;
import de.jexcellence.vote.service.VoteService;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VoteAdminHandler {

    private static final String PARAM_PLAYER = "player";
    private static final String PARAM_SERVICE = "service";
    private static final String PARAM_AGO = "ago";
    private static final String PARAM_KEY = "key";
    private static final String PORT = "port";
    private static final String DEBUG = "vote_admin.debug.";
    private static final String INFO = "vote_admin.info.";
    private static final String KEY_INFO = "vote_admin.key.";

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
                Map.entry("jexvote.key", this::onKey),
                Map.entry("jexvote.setstreak", this::onSetStreak),
                Map.entry("jexvote.debug-services", this::onDebugServices)
        );
    }

    /**
     * Prints, per configured site, its {@code service-name} and whether votes with that name have been
     * received, then lists received service names that match no configured site (the mismatches to fix).
     */
    private void onDebugServices(@NotNull CommandContext ctx) {
        CommandSender sender = ctx.sender();
        Map<String, VoteSite> sites = voteService.getVoteSites();
        voteService.receivedServiceNames().thenAccept(received -> {
            r18n().msg(DEBUG + "title").send(sender);
            Set<String> matched = matchConfiguredSites(sender, sites, received);
            reportOrphanServices(sender, received, matched);
        });
    }

    private @NotNull Set<String> matchConfiguredSites(@NotNull CommandSender sender,
                                                      @NotNull Map<String, VoteSite> sites,
                                                      @NotNull Map<String, Long> received) {
        Player viewer = sender instanceof Player player ? player : null;
        Set<String> matched = new HashSet<>();
        for (VoteSite site : sites.values()) {
            long last = -1L;
            for (Map.Entry<String, Long> rec : received.entrySet()) {
                if (rec.getKey().trim().equalsIgnoreCase(site.serviceName().trim())) {
                    last = Math.max(last, rec.getValue());
                    matched.add(rec.getKey());
                }
            }
            var line = last >= 0
                    ? r18n().msg(DEBUG + "site-receiving").with(PARAM_AGO, VoteFormat.agoEpoch(viewer, last))
                    : r18n().msg(DEBUG + "site-silent");
            line.with("site", site.id()).with(PARAM_SERVICE, site.serviceName()).send(sender);
        }
        return matched;
    }

    private void reportOrphanServices(@NotNull CommandSender sender,
                                      @NotNull Map<String, Long> received,
                                      @NotNull Set<String> matched) {
        Player viewer = sender instanceof Player player ? player : null;
        boolean anyOrphan = false;
        for (Map.Entry<String, Long> rec : received.entrySet()) {
            if (!matched.contains(rec.getKey())) {
                if (!anyOrphan) {
                    r18n().msg(DEBUG + "orphan-header").send(sender);
                    anyOrphan = true;
                }
                r18n().msg(DEBUG + "orphan").with(PARAM_SERVICE, rec.getKey())
                        .with(PARAM_AGO, VoteFormat.agoEpoch(viewer, rec.getValue())).send(sender);
            }
        }
        if (!anyOrphan) {
            r18n().msg(DEBUG + "all-matched").send(sender);
        }
    }

    private void onHelp(@NotNull CommandContext ctx) {
        List<HelpRenderer.Entry> entries = List.of(
                HelpRenderer.Entry.of("/jexvote info", "", "vote_admin.desc.info",
                        HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/jexvote reload", "", "vote_admin.desc.reload",
                        HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/jexvote reset", "<player>", "vote_admin.desc.reset",
                        HelpRenderer.Action.SUGGEST),
                HelpRenderer.Entry.of("/jexvote resetmonthly", "", "vote_admin.desc.resetmonthly",
                        HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/jexvote fakevote", "<player> [service]", "vote_admin.desc.fakevote",
                        HelpRenderer.Action.SUGGEST),
                HelpRenderer.Entry.of("/jexvote key", "", "vote_admin.desc.key",
                        HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/jexvote setstreak", "<player> <value>", "vote_admin.desc.setstreak",
                        HelpRenderer.Action.SUGGEST),
                HelpRenderer.Entry.of("/jexvote help", "", "vote_admin.desc.help",
                        HelpRenderer.Action.RUN)
        );
        new HelpRenderer("vote_admin").render(ctx.sender(), entries);
    }

    @SuppressWarnings("deprecation")
    private void onInfo(@NotNull CommandContext ctx) {
        CommandSender sender = ctx.sender();
        String editionKey = edition instanceof VoteEdition.PremiumEdition ? "premium" : "free";
        r18n().msg(INFO + "title").send(sender);
        sendAdminRow(sender, "edition", r18n().msg(INFO + "edition-" + editionKey).text(null));
        sendAdminRow(sender, "version", plugin.getDescription().getVersion());
        sendAdminRow(sender, "sites", String.valueOf(voteService.getVoteSites().size()));
        sendAdminRow(sender, PORT, String.valueOf(voteConfig.getServerPort()));
    }

    private static void sendAdminRow(@NotNull CommandSender sender, @NotNull String label, @NotNull String value) {
        r18n().msg("vote_admin.row")
                .with("label", r18n().msg("vote_admin.label." + label).text(null))
                .with("value", value)
                .send(sender);
    }

    /**
     * Prints the Votifier connection details a vote site needs: port, v2 token, and the RSA public key in
     * one-line (X.509 base64) and PEM form. The key is read from {@code rsa/public.key}, generated on first
     * server start.
     */
    private void onKey(@NotNull CommandContext ctx) {
        CommandSender sender = ctx.sender();
        Path keyFile = plugin.getDataFolder().toPath().resolve("rsa/public.key");
        String raw;
        try {
            raw = Files.readString(keyFile).replaceAll("\\s+", "");
        } catch (IOException e) {
            r18n().msg(KEY_INFO + "missing").prefix().send(sender);
            return;
        }
        String token = voteConfig.getServerToken();
        r18n().msg(KEY_INFO + "title").send(sender);
        sendAdminRow(sender, PORT, String.valueOf(voteConfig.getServerPort()));
        sendAdminRow(sender, "token", token.isEmpty() ? r18n().msg(KEY_INFO + "no-token").text(null) : token);
        r18n().msg(KEY_INFO + "one-line").with(PARAM_KEY, raw).send(sender);
        r18n().msg(KEY_INFO + "raw").with(PARAM_KEY, raw).send(sender);
        r18n().msg(KEY_INFO + "pem").send(sender);
        r18n().msg(KEY_INFO + "raw").with(PARAM_KEY, toPem(raw).replace("\n", "<newline>")).send(sender);
        r18n().msg(KEY_INFO + "hint").send(sender);
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
                voteConfig.getFreezeSettings(),
                rewardConfig.getDefaultRewards(),
                rewardConfig.getGuaranteedRewards(),
                rewardConfig.getStreakRewards(),
                rewardConfig.getSiteRewards(),
                voteConfig.getCommandsOnVote());
        r18n().msg("vote.reload").prefix().send(ctx.sender());
    }

    private void onReset(@NotNull CommandContext ctx) {
        OfflinePlayer target = ctx.require(PARAM_PLAYER, OfflinePlayer.class);
        String name = target.getName() != null ? target.getName() : target.getUniqueId().toString();
        voteService.resetPlayer(target.getUniqueId()).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                r18n().msg("vote.reset.success").prefix().with(PARAM_PLAYER, name).send(ctx.sender());
            } else {
                r18n().msg("vote.reset.not_found").prefix().with(PARAM_PLAYER, name).send(ctx.sender());
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
                r18n().msg("vote.fakevote.success").prefix()
                        .with(PARAM_PLAYER, playerName).with(PARAM_SERVICE, service).send(ctx.sender());
            } else {
                r18n().msg("vote.fakevote.failed").prefix().send(ctx.sender());
            }
        });
    }

    private void onSetStreak(@NotNull CommandContext ctx) {
        OfflinePlayer target = ctx.require(PARAM_PLAYER, OfflinePlayer.class);
        int value = ctx.require("value", Long.class).intValue();
        String name = target.getName() != null ? target.getName() : target.getUniqueId().toString();

        if (value < 0) {
            r18n().msg("vote.setstreak.invalid").prefix().send(ctx.sender());
            return;
        }

        voteService.setStreak(target.getUniqueId(), value).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                r18n().msg("vote.setstreak.success").prefix()
                        .with(PARAM_PLAYER, name)
                        .with("value", String.valueOf(value))
                        .send(ctx.sender());
            } else {
                r18n().msg("vote.setstreak.not_found").prefix()
                        .with(PARAM_PLAYER, name)
                        .send(ctx.sender());
            }
        });
    }

    private static R18nManager r18n() { return R18nManager.getInstance(); }
}
