package de.jexcellence.vote.command;

import de.jexcellence.vote.view.VoteStreakView;
import de.jexcellence.vote.view.VotePartyView;
import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.command.help.HelpRenderer;
import de.jexcellence.vote.config.VoteFeatures;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.vote.service.StreakFreezeService;
import de.jexcellence.vote.bedrock.VoteBedrockForms;
import de.jexcellence.vote.service.VoteGiftService;
import de.jexcellence.vote.service.VoteLeaderboardService;
import de.jexcellence.vote.service.VoteService;
import de.jexcellence.vote.view.VoteLeaderboardView;
import de.jexcellence.vote.view.VoteOverviewView;
import de.jexcellence.vote.view.VoteRewardsView;
import de.jexcellence.vote.view.VoteShopView;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class VoteCommandHandler {

    private static final String PARAM_TARGET = "target";
    private static final String PARAM_STREAK = "streak";
    private static final String PARAM_PLAYER = "player";
    private static final String PARAM_COUNT = "count";

    private final VoteService voteService;
    private final VoteLeaderboardService leaderboardService;
    private final VoteFeatures features;
    private final VoteOverviewView overviewView;
    private final VoteRewardsView rewardsView;
    private final VoteLeaderboardView leaderboardView;
    private final StreakFreezeService streakFreezeService;
    private final VoteGiftService voteGiftService;
    private VoteShopView shopView;
    private @Nullable VoteStreakView streakView;
    private @Nullable VotePartyView partyView;
    private @Nullable VoteBedrockForms bedrockForms;

    @SuppressWarnings("java:S107")
    public VoteCommandHandler(@NotNull VoteService voteService,
                              @NotNull VoteLeaderboardService leaderboardService,
                              @NotNull VoteFeatures features,
                              @NotNull VoteOverviewView overviewView,
                              @NotNull VoteRewardsView rewardsView,
                              @NotNull VoteLeaderboardView leaderboardView,
                              @NotNull StreakFreezeService streakFreezeService,
                              @NotNull VoteGiftService voteGiftService) {
        this.voteService = voteService;
        this.leaderboardService = leaderboardService;
        this.features = features;
        this.overviewView = overviewView;
        this.rewardsView = rewardsView;
        this.leaderboardView = leaderboardView;
        this.streakFreezeService = streakFreezeService;
        this.voteGiftService = voteGiftService;
    }

    public @NotNull Map<String, CommandHandler> handlerMap() {
        return Map.ofEntries(
                Map.entry("vote", this::onVote),
                Map.entry("vote.help", this::onHelp),
                Map.entry("vote.sites", this::onSites),
                Map.entry("vote.stats", this::onStats),
                Map.entry("vote.top", this::onTop),
                Map.entry("vote.rewards", this::onRewards),
                Map.entry("vote.freeze", this::onFreeze),
                Map.entry("vote.gift", this::onGift),
                Map.entry("vote.shop", this::onShop),
                Map.entry("vote.streak", this::onStreak),
                Map.entry("vote.party", this::onParty)
        );
    }

    /** Sets the vote-token shop view (wired post-construction in JExVote). */
    public void setShopView(@NotNull VoteShopView view) { this.shopView = view; }

    /** Views behind {@code /vote streak} and {@code /vote party}. */
    public void setStreakAndPartyViews(@NotNull VoteStreakView streak, @NotNull VotePartyView party) {
        this.streakView = streak;
        this.partyView = party;
    }

    /** Sets the Bedrock forms handler (wired post-construction when Floodgate is present). */
    public void setBedrockForms(@Nullable VoteBedrockForms forms) { this.bedrockForms = forms; }

    private boolean isBedrock(@NotNull Player player) {
        return bedrockForms != null && bedrockForms.isBedrock(player);
    }

    private void onShop(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElse(null);
        if (player == null) {
            r18n().msg("vote.shop.players_only").prefix().send(ctx.sender());
            return;
        }
        if (!features.shop() || shopView == null) {
            r18n().msg("vote.shop.unavailable").prefix().send(player);
            return;
        }
        if (isBedrock(player)) {
            bedrockForms.openShop(player);
            return;
        }
        shopView.open(player);
    }

    private void onStreak(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElse(null);
        if (player == null) {
            r18n().msg("vote.shop.players_only").prefix().send(ctx.sender());
            return;
        }
        if (!features.streaks() || streakView == null) {
            r18n().msg("vote.streak.unavailable").prefix().send(player);
            return;
        }
        if (isBedrock(player)) {
            bedrockForms.openStreaks(player);
            return;
        }
        streakView.open(player);
    }

    private void onParty(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElse(null);
        if (!features.party() || partyView == null) {
            r18n().msg("vote.party.unavailable").prefix().send(ctx.sender());
            return;
        }
        if (player == null) {
            rewardsView.sendTextSummary(ctx.sender());
            return;
        }
        if (isBedrock(player)) {
            bedrockForms.openParty(player);
            return;
        }
        partyView.open(player);
    }

    private void onRewards(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElse(null);
        if (player != null) {
            if (isBedrock(player)) {
                bedrockForms.openRewards(player);
                return;
            }
            rewardsView.open(player);
        } else {
            rewardsView.sendTextSummary(ctx.sender());
        }
    }

    private void onVote(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElseThrow();
        if (isBedrock(player)) {
            bedrockForms.openOverview(player);
            return;
        }
        overviewView.open(player);
    }

    private void onFreeze(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElseThrow();
        if (!features.freezes()) {
            r18n().msg("vote.freeze.disabled").prefix().send(player);
            return;
        }
        int cost = streakFreezeService.settings().costPoints();
        int max = streakFreezeService.resolveMax(player);
        streakFreezeService.purchase(player)
                .thenAccept(result -> VoteRewardsView.sendFreezeResult(player, result, cost, max));
    }

    private void onGift(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElseThrow();
        if (!features.gifts()) {
            r18n().msg("vote.gift.disabled").prefix().send(player);
            return;
        }
        String target = ctx.get(PARAM_TARGET, String.class).orElse("").trim();
        if (target.isEmpty()) {
            r18n().msg("vote.gift.usage").prefix().send(player);
            return;
        }

        CompletableFuture<VoteGiftService.GiftOutcome> future;
        if (target.equalsIgnoreCase("random")) {
            future = voteGiftService.giftRandom(player);
        } else {
            OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(target);
            if (offline == null) {
                r18n().msg("vote.gift.target_not_found").prefix().with(PARAM_TARGET, target).send(player);
                return;
            }
            future = voteGiftService.gift(player, offline);
        }
        future.thenAccept(outcome -> handleGiftOutcome(player, outcome));
    }

    private void handleGiftOutcome(@NotNull Player gifter, @NotNull VoteGiftService.GiftOutcome outcome) {
        String targetName = outcome.targetName() != null
                ? outcome.targetName() : r18n().msg("vote.unknown-player").text(gifter);
        switch (outcome.result()) {
            case SUCCESS -> notifyGiftSuccess(gifter, outcome, targetName);
            case DISABLED -> r18n().msg("vote.gift.disabled").prefix().send(gifter);
            case GIFTER_NO_PROFILE -> r18n().msg("vote.gift.gifter_no_profile").prefix().send(gifter);
            case NOT_VOTED_TODAY -> r18n().msg("vote.gift.not_voted").prefix().send(gifter);
            case LIMIT_REACHED -> r18n().msg("vote.gift.limit").prefix().send(gifter);
            case SELF_GIFT -> r18n().msg("vote.gift.self").prefix().send(gifter);
            case TARGET_NOT_FOUND -> r18n().msg("vote.gift.target_not_found").prefix()
                    .with(PARAM_TARGET, targetName).send(gifter);
            case ALREADY_ADVANCED -> r18n().msg("vote.gift.already_advanced").prefix()
                    .with(PARAM_TARGET, targetName).send(gifter);
            case NO_RANDOM_TARGET -> r18n().msg("vote.gift.no_random").prefix().send(gifter);
            default -> r18n().msg("vote.gift.error").prefix().send(gifter);
        }
    }

    private void notifyGiftSuccess(@NotNull Player gifter,
                                   @NotNull VoteGiftService.GiftOutcome outcome,
                                   @NotNull String targetName) {
        r18n().msg("vote.gift.sent").prefix()
                .with(PARAM_TARGET, targetName)
                .with(PARAM_STREAK, VoteFormat.days(gifter, outcome.receiverStreak()))
                .with("remaining", String.valueOf(outcome.remainingToday()))
                .send(gifter);

        Player receiver = Bukkit.getPlayerExact(targetName);
        if (receiver != null && receiver.isOnline()) {
            r18n().msg("vote.gift.received").prefix()
                    .with("gifter", gifter.getName())
                    .with(PARAM_STREAK, VoteFormat.days(receiver, outcome.receiverStreak()))
                    .send(receiver);
        }
    }

    private void onHelp(@NotNull CommandContext ctx) {
        List<HelpRenderer.Entry> entries = new ArrayList<>();
        entries.add(HelpRenderer.Entry.of("/vote", "", "vote_help.desc.vote", List.of("v"), HelpRenderer.Action.RUN));
        entries.add(HelpRenderer.Entry.of("/vote sites", "", "vote_help.desc.sites", HelpRenderer.Action.RUN));
        entries.add(HelpRenderer.Entry.of("/vote stats", "[player]", "vote_help.desc.stats",
                List.of("info"), HelpRenderer.Action.SUGGEST));
        entries.add(HelpRenderer.Entry.of("/vote top", "[count]", "vote_help.desc.top",
                List.of("leaderboard", "lb"), HelpRenderer.Action.SUGGEST));
        entries.add(HelpRenderer.Entry.of("/vote rewards", "", "vote_help.desc.rewards-menu",
                List.of("economy", "eco"), HelpRenderer.Action.RUN));
        if (features.streaks()) {
            entries.add(HelpRenderer.Entry.of("/vote streak", "", "vote_help.desc.streak",
                    List.of("streaks"), HelpRenderer.Action.RUN));
        }
        if (features.party()) {
            entries.add(HelpRenderer.Entry.of("/vote party", "", "vote_help.desc.party", HelpRenderer.Action.RUN));
        }
        if (features.shop()) {
            entries.add(HelpRenderer.Entry.of("/vote shop", "", "vote_help.desc.shop",
                    List.of("store", "tokens"), HelpRenderer.Action.RUN));
        }
        if (features.freezes()) {
            entries.add(HelpRenderer.Entry.of("/vote freeze", "", "vote_help.desc.freeze",
                    List.of("freezes"), HelpRenderer.Action.RUN));
        }
        if (features.gifts()) {
            entries.add(HelpRenderer.Entry.of("/vote gift", "<player|random>", "vote_help.desc.gift",
                    HelpRenderer.Action.SUGGEST));
        }
        entries.add(HelpRenderer.Entry.of("/vote help", "", "vote_help.desc.help", HelpRenderer.Action.RUN));
        new HelpRenderer("vote_help").render(ctx.sender(), entries);
    }

    private void onSites(@NotNull CommandContext ctx) {
        Map<String, VoteSite> sites = voteService.getVoteSites();
        if (sites.isEmpty()) {
            r18n().msg("vote.sites.no_sites").prefix().send(ctx.sender());
            return;
        }
        r18n().msg("vote.sites.title").with(PARAM_COUNT, String.valueOf(sites.size())).send(ctx.sender());
        for (VoteSite site : sites.values()) {
            var entry = site.voteUrl() != null
                    ? r18n().msg("vote.sites.entry").with("name", site.displayName()).with("url", site.voteUrl())
                    : r18n().msg("vote.sites.entry_plain").with("name", site.displayName())
                            .with("service", site.serviceName());
            entry.send(ctx.sender());
        }
    }

    private void onStats(@NotNull CommandContext ctx) {
        var explicitTarget = ctx.get("player", OfflinePlayer.class);
        Player self = ctx.asPlayer().orElse(null);

        if (explicitTarget.isEmpty() && self != null) {
            if (isBedrock(self)) {
                bedrockForms.openOverview(self);
                return;
            }
            overviewView.open(self);
            return;
        }

        OfflinePlayer target = explicitTarget.orElseGet(() -> ctx.asPlayer().orElseThrow());
        Player viewer = self;
        voteService.getPlayerStats(target.getUniqueId()).thenAccept(stats -> {
            String name = target.getName() != null ? target.getName() : target.getUniqueId().toString();
            r18n().msg("vote.stats.title").with(PARAM_PLAYER, name).send(ctx.sender());
            sendStatRow(ctx, viewer, "total", VoteFormat.number(viewer, stats.totalVotes()));
            sendStatRow(ctx, viewer, "monthly", VoteFormat.number(viewer, stats.monthlyVotes()));
            sendStatRow(ctx, viewer, "streak", VoteFormat.days(viewer, stats.currentStreak()));
            sendStatRow(ctx, viewer, "highest", VoteFormat.days(viewer, stats.highestStreak()));
            sendStatRow(ctx, viewer, "points", VoteFormat.number(viewer, stats.votePoints()));
            sendStatRow(ctx, viewer, "last-vote", VoteFormat.ago(viewer, stats.lastVoteAt()));
        });
    }

    private static void sendStatRow(@NotNull CommandContext ctx, @Nullable Player viewer,
                                    @NotNull String label, @NotNull String value) {
        r18n().msg("vote.stats.row")
                .with("label", r18n().msg("vote.stats.label." + label).text(viewer))
                .with("value", value)
                .send(ctx.sender());
    }

    private void onTop(@NotNull CommandContext ctx) {
        Player self = ctx.asPlayer().orElse(null);
        if (self != null && features.leaderboard()) {
            if (isBedrock(self)) {
                bedrockForms.openLeaderboard(self);
                return;
            }
            leaderboardView.open(self);
            return;
        }

        int count = ctx.get(PARAM_COUNT, Long.class).map(Long::intValue).orElse(10);
        leaderboardService.getAllTimeTop(Math.min(count, 50)).thenAccept(top -> {
            if (top.isEmpty()) {
                r18n().msg("vote.leaderboard.empty").prefix().send(ctx.sender());
                return;
            }
            r18n().msg("vote.leaderboard.title").send(ctx.sender());
            for (int i = 0; i < top.size(); i++) {
                VoteSnapshot entry = top.get(i);
                String name = entry.playerName() != null
                        ? entry.playerName() : r18n().msg("vote.unknown-player").text(self);
                r18n().msg("vote.leaderboard.entry")
                        .with("rank", String.valueOf(i + 1))
                        .with(PARAM_PLAYER, name)
                        .with("votes", VoteFormat.number(self, entry.totalVotes()))
                        .send(ctx.sender());
            }
        });
    }

    private static R18nManager r18n() { return R18nManager.getInstance(); }
}
