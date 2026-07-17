package de.jexcellence.vote.command;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.command.help.HelpRenderer;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.vote.service.StreakFreezeService;
import de.jexcellence.vote.service.VoteGiftService;
import de.jexcellence.vote.service.VoteLeaderboardService;
import de.jexcellence.vote.service.VoteService;
import de.jexcellence.vote.view.VoteLeaderboardView;
import de.jexcellence.vote.view.VoteOverviewView;
import de.jexcellence.vote.view.VoteRewardsView;
import de.jexcellence.vote.view.VoteShopView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class VoteCommandHandler {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final VoteService voteService;
    private final VoteLeaderboardService leaderboardService;
    private final VoteConfig voteConfig;
    private final VoteOverviewView overviewView;
    private final VoteRewardsView rewardsView;
    private final VoteLeaderboardView leaderboardView;
    private final StreakFreezeService streakFreezeService;
    private final VoteGiftService voteGiftService;
    private VoteShopView shopView;

    @SuppressWarnings("java:S107")
    public VoteCommandHandler(@NotNull VoteService voteService,
                              @NotNull VoteLeaderboardService leaderboardService,
                              @NotNull VoteConfig voteConfig,
                              @NotNull VoteOverviewView overviewView,
                              @NotNull VoteRewardsView rewardsView,
                              @NotNull VoteLeaderboardView leaderboardView,
                              @NotNull StreakFreezeService streakFreezeService,
                              @NotNull VoteGiftService voteGiftService) {
        this.voteService = voteService;
        this.leaderboardService = leaderboardService;
        this.voteConfig = voteConfig;
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
                Map.entry("vote.shop", this::onShop)
        );
    }

    /** Sets the vote-token shop view (wired post-construction in JExVote). */
    public void setShopView(@NotNull VoteShopView view) { this.shopView = view; }

    private void onShop(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElse(null);
        if (player == null) {
            r18n().msg("vote.shop.players_only").prefix().send(ctx.sender());
            return;
        }
        if (!voteConfig.isFeatureShop() || shopView == null) {
            r18n().msg("vote.shop.unavailable").prefix().send(player);
            return;
        }
        shopView.open(player);
    }

    private void onRewards(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElse(null);
        if (player != null) {
            rewardsView.open(player);
        } else {
            rewardsView.sendTextSummary(ctx.sender());
        }
    }

    private void onVote(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElseThrow();
        overviewView.open(player);
    }

    private void onFreeze(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElseThrow();
        int cost = streakFreezeService.settings().costPoints();
        int max = streakFreezeService.resolveMax(player);

        streakFreezeService.purchase(player).thenAccept(result -> {
            switch (result) {
                case SUCCESS -> r18n().msg("vote.freeze.bought").prefix()
                        .with("cost", String.valueOf(cost))
                        .send(player);
                case DISABLED -> r18n().msg("vote.freeze.disabled").prefix().send(player);
                case AT_MAX -> r18n().msg("vote.freeze.at_max").prefix()
                        .with("max", String.valueOf(max))
                        .send(player);
                case NOT_ENOUGH_POINTS -> r18n().msg("vote.freeze.not_enough").prefix()
                        .with("cost", String.valueOf(cost))
                        .send(player);
                case NO_PROFILE -> r18n().msg("vote.freeze.no_profile").prefix().send(player);
                default -> r18n().msg("vote.freeze.error").prefix().send(player);
            }
        });
    }

    private void onGift(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElseThrow();
        String target = ctx.get("target", String.class).orElse("").trim();
        if (target.isEmpty()) {
            r18n().msg("vote.gift.usage").prefix().send(player);
            return;
        }

        CompletableFuture<VoteGiftService.GiftOutcome> future;
        if (target.equalsIgnoreCase("random")) {
            future = voteGiftService.giftRandom(player);
        } else {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(target);
            future = voteGiftService.gift(player, offline);
        }
        future.thenAccept(outcome -> handleGiftOutcome(player, outcome));
    }

    private void handleGiftOutcome(@NotNull Player gifter, @NotNull VoteGiftService.GiftOutcome outcome) {
        String targetName = outcome.targetName() != null ? outcome.targetName() : "?";
        switch (outcome.result()) {
            case SUCCESS -> notifyGiftSuccess(gifter, outcome, targetName);
            case DISABLED -> r18n().msg("vote.gift.disabled").prefix().send(gifter);
            case GIFTER_NO_PROFILE -> r18n().msg("vote.gift.gifter_no_profile").prefix().send(gifter);
            case NOT_VOTED_TODAY -> r18n().msg("vote.gift.not_voted").prefix().send(gifter);
            case LIMIT_REACHED -> r18n().msg("vote.gift.limit").prefix().send(gifter);
            case SELF_GIFT -> r18n().msg("vote.gift.self").prefix().send(gifter);
            case TARGET_NOT_FOUND -> r18n().msg("vote.gift.target_not_found").prefix()
                    .with("target", targetName).send(gifter);
            case ALREADY_ADVANCED -> r18n().msg("vote.gift.already_advanced").prefix()
                    .with("target", targetName).send(gifter);
            case NO_RANDOM_TARGET -> r18n().msg("vote.gift.no_random").prefix().send(gifter);
            default -> r18n().msg("vote.gift.error").prefix().send(gifter);
        }
    }

    private void notifyGiftSuccess(@NotNull Player gifter,
                                   @NotNull VoteGiftService.GiftOutcome outcome,
                                   @NotNull String targetName) {
        r18n().msg("vote.gift.sent").prefix()
                .with("target", targetName)
                .with("streak", String.valueOf(outcome.receiverStreak()))
                .with("remaining", String.valueOf(outcome.remainingToday()))
                .send(gifter);

        Player receiver = Bukkit.getPlayerExact(targetName);
        if (receiver != null && receiver.isOnline()) {
            r18n().msg("vote.gift.received").prefix()
                    .with("gifter", gifter.getName())
                    .with("streak", String.valueOf(outcome.receiverStreak()))
                    .send(receiver);
        }
    }

    private void onHelp(@NotNull CommandContext ctx) {
        // Styling lives entirely in the vote_help.* i18n keys; entries only
        // contribute the command, args, description and aliases. New subcommands
        // get one line here — no MiniMessage to hand-paint.
        List<HelpRenderer.Entry> entries = List.of(
                HelpRenderer.Entry.of("/vote", "", "Open the vote menu",
                        List.of("v"), HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/vote sites", "", "List all vote sites with links",
                        HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/vote stats", "[player]", "View vote statistics (opens the menu for yourself)",
                        List.of("info"), HelpRenderer.Action.SUGGEST),
                HelpRenderer.Entry.of("/vote top", "[count]", "Open the all-time leaderboard",
                        List.of("leaderboard", "lb"), HelpRenderer.Action.SUGGEST),
                HelpRenderer.Entry.of("/vote rewards", "", "Jackpot odds, multiplier, freeze, gift, points",
                        List.of("economy", "eco"), HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/vote shop", "", "Spend vote points on rewards",
                        List.of("store", "tokens"), HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/vote freeze", "", "Buy a Streak Freeze (you start with one free)",
                        List.of("freezes"), HelpRenderer.Action.RUN),
                HelpRenderer.Entry.of("/vote gift", "<player|random>", "Gift a streak day to a friend",
                        HelpRenderer.Action.SUGGEST),
                HelpRenderer.Entry.of("/vote help", "", "Show this help",
                        HelpRenderer.Action.RUN)
        );
        new HelpRenderer("vote_help").render(ctx.sender(), entries);
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
        var explicitTarget = ctx.get("player", OfflinePlayer.class);
        Player self = ctx.asPlayer().orElse(null);

        // Self lookup from a player → open the overview GUI (stats + points +
        // sites + navigation). Console or an explicit target → text summary.
        if (explicitTarget.isEmpty() && self != null) {
            overviewView.open(self);
            return;
        }

        OfflinePlayer target = explicitTarget.orElseGet(() -> ctx.asPlayer().orElseThrow());
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
        Player self = ctx.asPlayer().orElse(null);
        if (self != null && voteConfig.isFeatureLeaderboard()) {
            leaderboardView.open(self);
            return;
        }

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
