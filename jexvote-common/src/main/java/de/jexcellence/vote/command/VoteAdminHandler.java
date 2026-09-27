package de.jexcellence.vote.command;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.jextranslate.MessageBuilder;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.command.help.HelpRenderer;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.config.VoteFeatures;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.model.Vote;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.vote.service.RewardEconomy;
import de.jexcellence.vote.service.VotePartyService;
import de.jexcellence.vote.service.VoteService;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code /jexvote}: the server-owner surface. {@code info} prints one status panel (edition, Votifier, sites,
 * last received vote, every feature with the reason it is on or off, and the integrations), {@code key} the
 * details a vote site needs, {@code debug-services} which service names arrive, plus reload, resets and a test
 * vote.
 *
 * @author JExcellence
 */
public final class VoteAdminHandler {

    private static final String PARAM_PLAYER = "player";
    private static final String PARAM_SERVICE = "service";
    private static final String PARAM_AGO = "ago";
    private static final String PARAM_KEY = "key";
    private static final String PARAM_VALUE = "value";
    private static final String PARAM_LABEL = "label";
    private static final String PORT = "port";
    private static final String DEBUG = "vote_admin.debug.";
    private static final String STATUS = "vote_admin.status.";
    private static final String KEY_INFO = "vote_admin.key.";
    private static final String TONE_OK = "ok";
    private static final String TONE_BAD = "bad";
    private static final String TONE_WARN = "warn";
    private static final String TONE_PLAIN = "plain";
    private static final String VALUE_ON = "feature-on";
    private static final String VALUE_OFF = "feature-off";

    private final JavaPlugin plugin;
    private final VoteService voteService;
    private final VoteConfig voteConfig;
    private final VoteFeatures features;
    private final AdminStatus status;

    public VoteAdminHandler(@NotNull JavaPlugin plugin,
                            @NotNull VoteService voteService,
                            @NotNull VoteConfig voteConfig,
                            @NotNull VoteFeatures features,
                            @NotNull AdminStatus status) {
        this.plugin = plugin;
        this.voteService = voteService;
        this.voteConfig = voteConfig;
        this.features = features;
        this.status = status;
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

    // ── Help ─────────────────────────────────────────────────────────────

    private void onHelp(@NotNull CommandContext ctx) {
        List<HelpRenderer.Entry> entries = List.of(
                HelpRenderer.Entry.of("/jexvote info", "", "vote_admin.desc.overview", HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/jexvote key", "", "vote_admin.desc.key", HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/jexvote debug-services", "", "vote_admin.desc.debug-services",
                        HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/jexvote fakevote", "<player> [service]", "vote_admin.desc.fakevote",
                        HelpRenderer.Action.SUGGEST),
                HelpRenderer.Entry.of("/jexvote reload", "", "vote_admin.desc.reload", HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/jexvote setstreak", "<player> <value>", "vote_admin.desc.setstreak",
                        HelpRenderer.Action.SUGGEST),
                HelpRenderer.Entry.of("/jexvote reset", "<player>", "vote_admin.desc.reset",
                        HelpRenderer.Action.SUGGEST),
                HelpRenderer.Entry.of("/jexvote resetmonthly", "", "vote_admin.desc.resetmonthly",
                        HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/jexvote help", "", "vote_admin.desc.help", HelpRenderer.Action.RUN)
        );
        new HelpRenderer("vote_admin").render(ctx.sender(), entries);
    }

    // ── Status panel ─────────────────────────────────────────────────────

    private void onInfo(@NotNull CommandContext ctx) {
        CommandSender sender = ctx.sender();
        Player viewer = sender instanceof Player player ? player : null;
        voteService.receivedServiceNames().thenAccept(received -> {
            r18n().msg(STATUS + "title").send(sender);
            sendSetup(sender, viewer, received);
            sendFeatures(sender, viewer);
            sendIntegrations(sender, viewer);
            section(sender, viewer, "next");
            r18n().msg(STATUS + "tip.key").send(sender);
            r18n().msg(STATUS + "tip.debug").send(sender);
            r18n().msg(STATUS + "tip.test").send(sender);
        });
    }

    @SuppressWarnings("deprecation")
    private void sendSetup(@NotNull CommandSender sender, @Nullable Player viewer,
                           @NotNull Map<String, Long> received) {
        section(sender, viewer, "setup");
        int max = features.edition().maxVoteSites();
        String edition = features.edition().hasSiteLimit()
                ? r18n().msg(STATUS + "value.edition-free").with("max", max).text(viewer)
                : r18n().msg(STATUS + "value.edition-premium").text(viewer);
        row(sender, viewer, "edition", TONE_PLAIN, edition);
        row(sender, viewer, "version", TONE_PLAIN, plugin.getDescription().getVersion());
        if (status.votifierRunning()) {
            row(sender, viewer, "votifier", TONE_OK, r18n().msg(STATUS + "value.votifier-on")
                    .with(PORT, voteConfig.getServerPort()).text(viewer));
        } else {
            row(sender, viewer, "votifier", TONE_BAD, text(viewer, "value.votifier-off"));
        }
        sendSites(sender, viewer, max);
        long last = received.values().stream().mapToLong(Long::longValue).max().orElse(-1L);
        if (last < 0L) {
            row(sender, viewer, "last-vote", TONE_WARN, text(viewer, "value.last-vote-never"));
        } else {
            row(sender, viewer, "last-vote", TONE_OK, VoteFormat.agoEpoch(viewer, last));
        }
    }

    private void sendSites(@NotNull CommandSender sender, @Nullable Player viewer, int max) {
        int loaded = voteService.getVoteSites().size();
        int configured = status.configuredSiteCount();
        if (loaded == 0) {
            row(sender, viewer, "sites", TONE_BAD, text(viewer, "value.sites-none"));
        } else if (configured > loaded) {
            row(sender, viewer, "sites", TONE_WARN, r18n().msg(STATUS + "value.sites-limited")
                    .with("loaded", loaded).with("configured", configured).with("max", max).text(viewer));
        } else {
            row(sender, viewer, "sites", TONE_OK, r18n().msg(STATUS + "value.sites-loaded")
                    .with("count", loaded).text(viewer));
        }
        long withoutLink = voteService.getVoteSites().values().stream().filter(site -> site.voteUrl() == null).count();
        if (withoutLink > 0L) {
            row(sender, viewer, "links", TONE_WARN, r18n().msg(STATUS + "value.links-missing")
                    .with("count", withoutLink).text(viewer));
        }
    }

    private void sendFeatures(@NotNull CommandSender sender, @Nullable Player viewer) {
        section(sender, viewer, "features");
        if (features.streaks()) {
            String mode = voteConfig.getStreakClaimMode() == VoteConfig.StreakClaimMode.MANUAL ? "manual" : "auto";
            row(sender, viewer, "streaks", TONE_OK, text(viewer, "value.streaks-" + mode));
        } else {
            onOff(sender, viewer, "streaks", false);
        }
        onOff(sender, viewer, "leaderboard", features.leaderboard());
        sendGated(sender, viewer, "shop", features.shopState(), () -> r18n().msg(STATUS + "value.shop-on")
                .with("count", status.shopItemCount()).text(viewer));
        sendParty(sender, viewer);
        sendGated(sender, viewer, "weekend", features.weekendBonusState(), () -> r18n().msg(STATUS + "value.weekend-on")
                .with("factor", VoteFormat.multiplier(viewer, voteConfig.getWeekendMultiplierFactor()))
                .with("days", VoteFormat.dayNames(viewer, voteConfig.getWeekendMultiplierDays()))
                .text(viewer));
        onOff(sender, viewer, "freeze", features.freezes());
        onOff(sender, viewer, "gifts", features.gifts());
        onOff(sender, viewer, "reconciliation", voteConfig.getReconciliationSettings().enabled());
        sendGated(sender, viewer, "proxy",
                VoteFeatures.state(features.edition().proxySyncEnabled(), voteConfig.isProxyEnabled()),
                () -> text(viewer, "value.on"));
        if (status.restApiRunning()) {
            row(sender, viewer, "rest-api", TONE_OK, r18n().msg(STATUS + "value.rest-on")
                    .with(PORT, voteConfig.getRestApiConfig().port()).text(viewer));
        } else if (voteConfig.getRestApiConfig().enabled()) {
            row(sender, viewer, "rest-api", TONE_BAD, text(viewer, "value.rest-failed"));
        } else {
            onOff(sender, viewer, "rest-api", false);
        }
    }

    private void sendParty(@NotNull CommandSender sender, @Nullable Player viewer) {
        VotePartyService party = status.party();
        VoteFeatures.State state = features.partyState();
        if (state == VoteFeatures.State.ON && party == null) {
            row(sender, viewer, "party", TONE_WARN, text(viewer, "value.party-restart"));
            return;
        }
        sendGated(sender, viewer, "party", state, () -> r18n().msg(STATUS + "value.party-on")
                .with("current", party == null ? 0 : party.getCurrentVotes())
                .with("target", party == null ? 0 : party.getTargetVotes())
                .text(viewer));
    }

    private void sendIntegrations(@NotNull CommandSender sender, @Nullable Player viewer) {
        section(sender, viewer, "integrations");
        RewardEconomy.Provider economy = RewardEconomy.activeProvider();
        String economyKey = "value.economy-" + economy.name().toLowerCase(Locale.ROOT);
        row(sender, viewer, "economy", economy == RewardEconomy.Provider.NONE ? TONE_WARN : TONE_OK,
                text(viewer, economyKey));
        row(sender, viewer, "placeholders", status.placeholdersHooked() ? TONE_OK : TONE_PLAIN,
                text(viewer, status.placeholdersHooked() ? "value.placeholders-on" : "value.placeholders-off"));
        row(sender, viewer, "bedrock", status.bedrockFormsHooked() ? TONE_OK : TONE_PLAIN,
                text(viewer, status.bedrockFormsHooked() ? "value.bedrock-on" : "value.bedrock-off"));
    }

    /** Supplies the "on" value of a gated feature. */
    @FunctionalInterface
    private interface OnValue {
        @NotNull String text();
    }

    private void sendGated(@NotNull CommandSender sender, @Nullable Player viewer, @NotNull String label,
                           @NotNull VoteFeatures.State state, @NotNull OnValue onValue) {
        switch (state) {
            case ON -> row(sender, viewer, label, TONE_OK, onValue.text());
            case PREMIUM_ONLY -> row(sender, viewer, label, TONE_PLAIN, text(viewer, "value.premium"));
            default -> row(sender, viewer, label, TONE_PLAIN, text(viewer, "value.off"));
        }
    }

    private void onOff(@NotNull CommandSender sender, @Nullable Player viewer, @NotNull String label, boolean on) {
        row(sender, viewer, label, on ? TONE_OK : TONE_PLAIN, text(viewer, "value." + (on ? VALUE_ON : VALUE_OFF)));
    }

    private static void section(@NotNull CommandSender sender, @Nullable Player viewer, @NotNull String name) {
        r18n().msg(STATUS + "heading").with("name", text(viewer, "section." + name)).send(sender);
    }

    private static void row(@NotNull CommandSender sender, @Nullable Player viewer, @NotNull String label,
                            @NotNull String tone, @NotNull String value) {
        String toned = r18n().msg("gui.common.value." + tone).with(PARAM_VALUE, value).miniMessage(viewer);
        r18n().msg(STATUS + "row")
                .with(PARAM_LABEL, text(viewer, "label." + label))
                .with(PARAM_VALUE, toned)
                .send(sender);
    }

    private static @NotNull String text(@Nullable Player viewer, @NotNull String statusKey) {
        return r18n().msg(STATUS + statusKey).text(viewer);
    }

    // ── Service check ────────────────────────────────────────────────────

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
            MessageBuilder line = last >= 0
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

    // ── Votifier key ─────────────────────────────────────────────────────

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

    private static void sendAdminRow(@NotNull CommandSender sender, @NotNull String label, @NotNull String value) {
        r18n().msg("vote_admin.row")
                .with(PARAM_LABEL, r18n().msg("vote_admin.label." + label).text(null))
                .with(PARAM_VALUE, value)
                .send(sender);
    }

    /** Wraps a base64 X.509 key in PEM armor with 64-char lines. */
    private static @NotNull String toPem(@NotNull String base64) {
        StringBuilder sb = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int offset = 0; offset < base64.length(); offset += 64) {
            sb.append(base64, offset, Math.min(offset + 64, base64.length())).append('\n');
        }
        return sb.append("-----END PUBLIC KEY-----").toString();
    }

    // ── Maintenance ──────────────────────────────────────────────────────

    private void onReload(@NotNull CommandContext ctx) {
        status.reload();
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
        String service = ctx.get(PARAM_SERVICE, String.class).orElse("TestService");

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
        int value = ctx.require(PARAM_VALUE, Long.class).intValue();
        String name = target.getName() != null ? target.getName() : target.getUniqueId().toString();

        if (value < 0) {
            r18n().msg("vote.setstreak.invalid").prefix().send(ctx.sender());
            return;
        }

        voteService.setStreak(target.getUniqueId(), value).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                r18n().msg("vote.setstreak.success").prefix()
                        .with(PARAM_PLAYER, name)
                        .with(PARAM_VALUE, String.valueOf(value))
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
