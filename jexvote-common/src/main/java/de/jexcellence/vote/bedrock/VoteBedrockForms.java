package de.jexcellence.vote.bedrock;

import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.vote.config.VoteShopItem;
import de.jexcellence.vote.reward.ChanceReward;
import de.jexcellence.vote.reward.LuckyReward;
import de.jexcellence.vote.service.MultiplierService;
import de.jexcellence.vote.service.RewardStatsService;
import de.jexcellence.vote.service.StreakClaimService;
import de.jexcellence.vote.service.StreakFreezeService;
import de.jexcellence.vote.service.VoteGiftService;
import de.jexcellence.vote.service.VoteLeaderboardService;
import de.jexcellence.vote.service.VotePartyService;
import de.jexcellence.vote.service.VoteRewardService;
import de.jexcellence.vote.service.VoteService;
import de.jexcellence.vote.service.VoteShopService;
import de.jexcellence.vote.view.VoteRewardDescriber;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Builds Cumulus (Bedrock) forms that mirror each JExVote GUI view.
 * <p>
 * Bedrock forms are text-based with simple buttons — they condense the
 * rich chest-GUI tile grid into a readable list format. Each form method
 * loads data async, then sends the form on the main thread via
 * {@link BedrockFormBridge#sendForm}.
 *
 * @author JExcellence
 * @since 3.3.0
 */
public final class VoteBedrockForms {

    private static final int LEADERBOARD_LIMIT = 20;
    private static final String NAV_BACK = "bedrock.nav.back";

    private final BedrockFormBridge bridge;
    private final VoteService voteService;
    private final VoteConfig voteConfig;
    private final VoteLeaderboardService leaderboardService;
    private final VoteRewardService rewardService;
    private final VoteRewardConfig rewardConfig;
    private final StreakClaimService claimService;
    private final MultiplierService multipliers;
    private final RewardStatsService stats;
    private final StreakFreezeService freezeService;
    private final VoteGiftService giftService;
    private @Nullable VotePartyService partyService;
    private @Nullable VoteShopService shopService;

    @SuppressWarnings("java:S107")
    public VoteBedrockForms(@NotNull BedrockFormBridge bridge,
                            @NotNull VoteService voteService,
                            @NotNull VoteConfig voteConfig,
                            @NotNull VoteLeaderboardService leaderboardService,
                            @NotNull VoteRewardService rewardService,
                            @NotNull VoteRewardConfig rewardConfig,
                            @NotNull StreakClaimService claimService,
                            @NotNull MultiplierService multipliers,
                            @NotNull RewardStatsService stats,
                            @NotNull StreakFreezeService freezeService,
                            @NotNull VoteGiftService giftService) {
        this.bridge = bridge;
        this.voteService = voteService;
        this.voteConfig = voteConfig;
        this.leaderboardService = leaderboardService;
        this.rewardService = rewardService;
        this.rewardConfig = rewardConfig;
        this.claimService = claimService;
        this.multipliers = multipliers;
        this.stats = stats;
        this.freezeService = freezeService;
        this.giftService = giftService;
    }

    public void setPartyService(@Nullable VotePartyService partyService) {
        this.partyService = partyService;
    }

    public void setShopService(@Nullable VoteShopService shopService) {
        this.shopService = shopService;
    }

    /** Returns {@code true} when the player connects via Bedrock/Floodgate. */
    public boolean isBedrock(@NotNull Player player) {
        return bridge.isBedrockPlayer(player);
    }

    // ── Overview ─────────────────────────────────────────────────────────

    public void openOverview(@NotNull Player player) {
        voteService.getPlayerStats(player.getUniqueId())
                .thenCombineAsync(voteService.voteCooldownsSeconds(player.getUniqueId()),
                        (playerStats, cooldowns) -> {
                    String body = buildOverviewBody(player, playerStats, cooldowns);

                    SimpleForm.Builder form = SimpleForm.builder()
                            .title(plain(player, "bedrock.overview.title"))
                            .content(body);

                    List<VoteSite> siteList = new ArrayList<>(voteService.getVoteSites().values());
                    addSiteButtons(form, player, siteList, cooldowns);
                    addOverviewNavButtons(form, player);

                    int siteCount = siteList.size();
                    form.validResultHandler(response ->
                            handleOverviewClick(player, response.clickedButtonId(), siteList, siteCount));

                    return form.build();
                }).thenAccept(form -> bridge.sendForm(player, form));
    }

    private @NotNull String buildOverviewBody(@NotNull Player player, @NotNull VoteSnapshot stats,
                                               @NotNull Map<String, Long> cooldowns) {
        StringBuilder body = new StringBuilder();
        body.append(plain(player, "bedrock.overview.streak"))
                .append(": ").append(stats.currentStreak())
                .append(" (").append(plain(player, "bedrock.overview.highest"))
                .append(": ").append(stats.highestStreak()).append(")\n");
        body.append(plain(player, "bedrock.overview.points"))
                .append(": ").append(stats.votePoints()).append("\n");
        body.append(plain(player, "bedrock.overview.total-votes"))
                .append(": ").append(stats.totalVotes()).append("\n");

        long readyCount = voteService.getVoteSites().values().stream()
                .filter(s -> cooldowns.getOrDefault(s.serviceName(), 0L) == 0)
                .count();
        long totalSites = voteService.getVoteSites().size();
        body.append("\n").append(readyCount).append("/").append(totalSites)
                .append(" ").append(plain(player, "bedrock.overview.sites-ready"));
        return body.toString();
    }

    private void addSiteButtons(@NotNull SimpleForm.Builder form, @NotNull Player player,
                                @NotNull List<VoteSite> siteList, @NotNull Map<String, Long> cooldowns) {
        for (VoteSite site : siteList) {
            long secs = cooldowns.getOrDefault(site.serviceName(), 0L);
            String label = secs == 0
                    ? "✔ " + site.displayName()
                    : "⏳ " + site.displayName() + " (" + formatCooldown(secs) + ")";
            form.button(label);
        }
    }

    private void addOverviewNavButtons(@NotNull SimpleForm.Builder form, @NotNull Player player) {
        form.button(plain(player, "bedrock.nav.leaderboard"));
        form.button(plain(player, "bedrock.nav.streaks"));
        form.button(plain(player, "bedrock.nav.rewards"));
        if (voteConfig.isFeatureShop() && shopService != null) {
            form.button(plain(player, "bedrock.nav.shop"));
        }
    }

    private void handleOverviewClick(@NotNull Player player, int idx,
                                     @NotNull List<VoteSite> siteList, int siteCount) {
        if (idx < siteCount) {
            VoteSite clicked = siteList.get(idx);
            if (clicked.voteUrl() != null) {
                player.sendMessage(Component.empty());
                player.sendMessage(Component.text("» ", NamedTextColor.GOLD)
                        .append(Component.text(clicked.displayName(), NamedTextColor.WHITE)));
                player.sendMessage(Component.text("  " + clicked.voteUrl(), NamedTextColor.GREEN));
                player.sendMessage(Component.empty());
            }
            return;
        }
        int navIdx = idx - siteCount;
        switch (navIdx) {
            case 0 -> openLeaderboard(player);
            case 1 -> openStreaks(player);
            case 2 -> openRewards(player);
            case 3 -> {
                if (shopService != null) {
                    openShop(player);
                }
            }
            default -> {
                // Intentionally ignored: out of range
            }
        }
    }

    // ── Leaderboard ──────────────────────────────────────────────────────

    public void openLeaderboard(@NotNull Player player) {
        leaderboardService.getAllTimeTop(LEADERBOARD_LIMIT).thenAccept(entries -> {
            String title = plain(player, "bedrock.leaderboard.title");
            StringBuilder body = new StringBuilder();
            body.append(plain(player, "bedrock.leaderboard.header")).append("\n\n");

            int rank = 1;
            for (VoteSnapshot e : entries) {
                String medal = switch (rank) {
                    case 1 -> "🥇";
                    case 2 -> "🥈";
                    case 3 -> "🥉";
                    default -> "#" + rank;
                };
                body.append(medal).append(" ").append(e.playerName())
                        .append(" — ").append(e.totalVotes()).append(" ")
                        .append(plain(player, "bedrock.leaderboard.votes"))
                        .append(" | ").append(plain(player, "bedrock.leaderboard.streak-label"))
                        .append(": ").append(e.currentStreak()).append("\n");
                rank++;
            }

            SimpleForm form = SimpleForm.builder()
                    .title(title)
                    .content(body.toString())
                    .button(plain(player, NAV_BACK))
                    .validResultHandler(response -> openOverview(player))
                    .build();
            bridge.sendForm(player, form);
        });
    }

    // ── Streaks ──────────────────────────────────────────────────────────

    public void openStreaks(@NotNull Player player) {
        voteService.getPlayerStats(player.getUniqueId())
                .thenCombineAsync(claimService.getClaimedDays(player.getUniqueId()),
                        (playerStats, claimed) -> {
                    StringBuilder body = new StringBuilder();
                    body.append(plain(player, "bedrock.streaks.header")).append("\n\n");

                    int streak = playerStats.currentStreak();
                    body.append(plain(player, "bedrock.streaks.current")).append(": ").append(streak).append("\n");
                    body.append(plain(player, "bedrock.streaks.highest")).append(": ")
                            .append(playerStats.highestStreak()).append("\n\n");
                    body.append(plain(player, "bedrock.streaks.milestones")).append(":\n");

                    List<Integer> claimableDays = appendMilestoneList(body, player, streak, claimed);

                    SimpleForm.Builder form = SimpleForm.builder()
                            .title(plain(player, "bedrock.streaks.title"))
                            .content(body.toString());

                    for (int day : claimableDays) {
                        form.button("★ " + plain(player, "bedrock.streaks.claim-day") + " " + day);
                    }
                    form.button(plain(player, NAV_BACK));

                    int claimCount = claimableDays.size();
                    form.validResultHandler(response ->
                            handleStreakClick(player, response.clickedButtonId(), claimableDays, claimCount));

                    return form.build();
                }).thenAccept(form -> bridge.sendForm(player, form));
    }

    private @NotNull List<Integer> appendMilestoneList(@NotNull StringBuilder body, @NotNull Player player,
                                                        int streak, @NotNull java.util.Set<Integer> claimed) {
        Map<Integer, List<AbstractReward>> streakRewards = rewardConfig.getStreakRewards();
        List<Integer> sortedDays = new ArrayList<>(streakRewards.keySet());
        sortedDays.sort(Integer::compareTo);
        List<Integer> claimableDays = new ArrayList<>();
        for (int day : sortedDays) {
            boolean alreadyClaimed = claimed.contains(day);
            String status;
            if (alreadyClaimed) {
                status = "✔ " + plain(player, "bedrock.streaks.claimed");
            } else if (streak >= day) {
                status = "★ " + plain(player, "bedrock.streaks.claimable");
                claimableDays.add(day);
            } else {
                status = "🔒 " + plain(player, "bedrock.streaks.locked");
            }
            body.append("  ").append(plain(player, "bedrock.streaks.day"))
                    .append(" ").append(day).append(": ").append(status).append("\n");
        }
        return claimableDays;
    }

    private void handleStreakClick(@NotNull Player player, int idx,
                                   @NotNull List<Integer> claimableDays, int claimCount) {
        if (idx < claimCount) {
            int targetDay = claimableDays.get(idx);
            claimService.claimMilestone(player, targetDay).thenAccept(result -> {
                if (result == StreakClaimService.ClaimResult.SUCCESS) {
                    r18n().msg("bedrock.streaks.claim-success")
                            .prefix().with("day", String.valueOf(targetDay))
                            .send(player);
                } else {
                    r18n().msg("bedrock.streaks.claim-failed").prefix().send(player);
                }
                openStreaks(player);
            });
        } else {
            openOverview(player);
        }
    }

    // ── Rewards hub ──────────────────────────────────────────────────────

    public void openRewards(@NotNull Player player) {
        CompletableFuture<Integer> pointsFuture = freezeService.getPoints(player.getUniqueId());
        CompletableFuture<Integer> freezeOwned = freezeService.getOwned(player.getUniqueId());

        pointsFuture.thenCombineAsync(freezeOwned, (points, owned) -> {
            String title = plain(player, "bedrock.rewards.title");
            StringBuilder body = new StringBuilder();
            body.append(plain(player, "bedrock.rewards.header")).append("\n\n");

            body.append(plain(player, "bedrock.rewards.points")).append(": ").append(points).append("\n\n");

            double factor = multipliers.current();
            if (factor > 1.0) {
                body.append("✦ ").append(plain(player, "bedrock.rewards.multiplier"))
                        .append(": x").append(String.format("%.1f", factor)).append("\n");
            }

            if (partyService != null) {
                int current = partyService.currentVotes();
                int target = partyService.targetVotes();
                body.append("\n").append(plain(player, "bedrock.rewards.party"))
                        .append(": ").append(current).append("/").append(target).append("\n");
            }

            body.append("\n").append(plain(player, "bedrock.rewards.freeze-owned"))
                    .append(": ").append(owned).append("\n");

            SimpleForm.Builder form = SimpleForm.builder()
                    .title(title)
                    .content(body.toString());

            List<String> actions = new ArrayList<>();
            form.button(plain(player, "bedrock.rewards.lucky-catalog"));
            actions.add("lucky");

            if (partyService != null) {
                form.button(plain(player, "bedrock.rewards.party-catalog"));
                actions.add("party");
            }

            if (voteConfig.isFeatureShop() && shopService != null) {
                form.button(plain(player, "bedrock.nav.shop"));
                actions.add("shop");
            }

            form.button(plain(player, "bedrock.rewards.buy-freeze")
                    + " (" + freezeService.settings().costPoints() + " pts)");
            actions.add("freeze");

            form.button(plain(player, NAV_BACK));
            actions.add("back");

            form.validResultHandler(response ->
                    handleRewardAction(player, actions, response.clickedButtonId()));

            return form.build();
        }).thenAccept(form -> bridge.sendForm(player, form));
    }

    private void handleRewardAction(@NotNull Player player, @NotNull List<String> actions, int idx) {
        if (idx < 0 || idx >= actions.size()) {
            return;
        }
        switch (actions.get(idx)) {
            case "lucky" -> openLucky(player);
            case "party" -> openParty(player);
            case "shop" -> openShop(player);
            case "freeze" -> handleFreezePurchase(player);
            default -> openOverview(player);
        }
    }

    private void handleFreezePurchase(@NotNull Player player) {
        freezeService.purchase(player).thenAccept(result -> {
            String key = switch (result) {
                case SUCCESS -> "vote.freeze.bought";
                case DISABLED -> "vote.freeze.disabled";
                case AT_MAX -> "vote.freeze.at_max";
                case NOT_ENOUGH_POINTS -> "vote.freeze.not_enough";
                case NO_PROFILE -> "vote.freeze.no_profile";
                default -> "vote.freeze.error";
            };
            r18n().msg(key).prefix()
                    .with("cost", String.valueOf(freezeService.settings().costPoints()))
                    .with("max", String.valueOf(freezeService.resolveMax(player)))
                    .send(player);
            openRewards(player);
        });
    }

    // ── Lucky catalog ────────────────────────────────────────────────────

    public void openLucky(@NotNull Player player) {
        String title = plain(player, "bedrock.lucky.title");
        StringBuilder body = new StringBuilder();
        body.append(plain(player, "bedrock.lucky.header")).append("\n\n");

        List<ChanceReward> chancePool = collectChanceRewards();
        chancePool.sort((a, b) -> Double.compare(b.getChance(), a.getChance()));

        for (ChanceReward r : chancePool) {
            String desc = VoteRewardDescriber.describe(r.getReward());
            double pct = r.getChance() * 100.0;
            body.append("  ").append(String.format("%.1f%%", pct))
                    .append(" — ").append(desc).append("\n");
        }

        LuckyReward luckyPool = rewardConfig.getVotePartyPool();
        if (luckyPool != null && !luckyPool.getEntries().isEmpty()) {
            body.append("\n").append(plain(player, "bedrock.lucky.pool-header")).append(":\n");
            double totalWeight = luckyPool.getEntries().stream().mapToDouble(LuckyReward.Entry::weight).sum();
            for (LuckyReward.Entry entry : luckyPool.getEntries()) {
                String desc = VoteRewardDescriber.describe(entry.reward());
                double pct = totalWeight > 0 ? (entry.weight() / totalWeight) * 100.0 : 0;
                body.append("  ").append(String.format("%.1f%%", pct))
                        .append(" — ").append(desc).append("\n");
            }
        }

        SimpleForm form = SimpleForm.builder()
                .title(title)
                .content(body.toString())
                .button(plain(player, NAV_BACK))
                .validResultHandler(response -> openRewards(player))
                .build();
        bridge.sendForm(player, form);
    }

    // ── Party catalog ────────────────────────────────────────────────────

    public void openParty(@NotNull Player player) {
        String title = plain(player, "bedrock.party.title");
        StringBuilder body = new StringBuilder();
        body.append(plain(player, "bedrock.party.header")).append("\n\n");

        if (partyService != null) {
            int current = partyService.currentVotes();
            int target = partyService.targetVotes();
            int remaining = Math.max(0, target - current);
            body.append(plain(player, "bedrock.party.progress"))
                    .append(": ").append(current).append("/").append(target)
                    .append(" (").append(remaining).append(" ").append(plain(player, "bedrock.party.remaining")).append(")\n\n");
        }

        var rewards = rewardConfig.getVotePartyRewards();
        if (rewards != null && !rewards.isEmpty()) {
            body.append(plain(player, "bedrock.party.rewards")).append(":\n");
            for (var r : rewards) {
                String desc = VoteRewardDescriber.describe(r);
                body.append("  • ").append(desc).append("\n");
            }
        }

        SimpleForm form = SimpleForm.builder()
                .title(title)
                .content(body.toString())
                .button(plain(player, NAV_BACK))
                .validResultHandler(response -> openRewards(player))
                .build();
        bridge.sendForm(player, form);
    }

    // ── Shop ─────────────────────────────────────────────────────────────

    public void openShop(@NotNull Player player) {
        if (shopService == null) {
            return;
        }
        shopService.getPoints(player.getUniqueId()).thenAccept(points -> {
            String title = plain(player, "bedrock.shop.title");
            StringBuilder body = new StringBuilder();
            body.append(plain(player, "bedrock.shop.header")).append("\n");
            body.append(plain(player, "bedrock.shop.balance")).append(": ").append(points).append("\n\n");

            List<VoteShopItem> buyable = new ArrayList<>(shopService.items());

            for (VoteShopItem item : buyable) {
                String desc = VoteRewardDescriber.describe(item.reward());
                boolean canAfford = points >= item.cost();
                String afford = canAfford ? "✔" : "✘";
                body.append("  ").append(afford).append(" ").append(item.name())
                        .append(" — ").append(item.cost()).append(" pts")
                        .append("\n    ").append(desc).append("\n");
            }

            SimpleForm.Builder form = SimpleForm.builder()
                    .title(title)
                    .content(body.toString());

            for (VoteShopItem item : buyable) {
                boolean canAfford = points >= item.cost();
                String label = (canAfford ? "✔ " : "✘ ") + item.name()
                        + " (" + item.cost() + " pts)";
                form.button(label);
            }
            form.button(plain(player, NAV_BACK));

            int itemCount = buyable.size();
            form.validResultHandler(response -> {
                int idx = response.clickedButtonId();
                if (idx < itemCount) {
                    VoteShopItem chosen = buyable.get(idx);
                    openShopConfirm(player, chosen);
                } else {
                    openRewards(player);
                }
            });

            bridge.sendForm(player, form.build());
        });
    }

    private void openShopConfirm(@NotNull Player player, @NotNull VoteShopItem item) {
        String title = plain(player, "bedrock.shop.confirm-title");
        String content = plain(player, "bedrock.shop.confirm-body")
                .replace("{item}", item.name())
                .replace("{cost}", String.valueOf(item.cost()));

        ModalForm form = ModalForm.builder()
                .title(title)
                .content(content)
                .button1(plain(player, "bedrock.shop.confirm-buy"))
                .button2(plain(player, "bedrock.shop.confirm-cancel"))
                .validResultHandler(response -> {
                    if (response.clickedButtonId() == 0) {
                        shopService.purchase(player, item).thenAccept(result -> {
                            String key = switch (result) {
                                case SUCCESS -> "bedrock.shop.bought";
                                case NOT_ENOUGH_POINTS -> "bedrock.shop.not-enough";
                                default -> "bedrock.shop.error";
                            };
                            r18n().msg(key).prefix()
                                    .with("item", item.name())
                                    .with("cost", String.valueOf(item.cost()))
                                    .send(player);
                            openShop(player);
                        });
                    } else {
                        openShop(player);
                    }
                })
                .build();
        bridge.sendForm(player, form);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static @NotNull String plain(@NotNull Player player, @NotNull String key) {
        return r18n().msg(key).toPlainString(player);
    }

    private static @NotNull R18nManager r18n() {
        return R18nManager.getInstance();
    }

    private static @NotNull String formatAgo(@NotNull Instant at) {
        Duration since = Duration.between(at, Instant.now());
        long days = since.toDays();
        if (days >= 1) {
            return days + "d ago";
        }
        long hours = since.toHours();
        if (hours >= 1) {
            return hours + "h ago";
        }
        return Math.max(1, since.toMinutes()) + "m ago";
    }

    private static @NotNull String formatCooldown(long seconds) {
        long s = Math.max(0L, seconds);
        long hours = s / 3600L;
        long minutes = (s % 3600L) / 60L;
        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return s + "s";
    }

    private @NotNull List<ChanceReward> collectChanceRewards() {
        List<ChanceReward> out = new ArrayList<>();
        for (var r : rewardConfig.getDefaultRewards()) {
            if (r instanceof ChanceReward cr) {
                out.add(cr);
            }
        }
        return out;
    }
}
