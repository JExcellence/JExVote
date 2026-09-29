package de.jexcellence.vote.bedrock;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.view.RewardViewHelper;
import de.jexcellence.jextranslate.MessageBuilder;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.config.VoteFeatures;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.config.VoteShopItem;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.model.VoteSite;
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
import de.jexcellence.vote.view.VoteLuckyView;
import de.jexcellence.vote.view.VotePrizeCatalogView.Prize;
import de.jexcellence.vote.view.VoteRewardDescriber;
import de.jexcellence.vote.view.VoteRewardsView;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * Cumulus (Bedrock) forms that mirror each JExVote chest view as a text body plus buttons. Every label and
 * line comes from the {@code bedrock.*} translation keys; numbers use the viewer's locale and currency is
 * written out, because Bedrock forms cannot show the resource-pack icons.
 *
 * @author JExcellence
 * @since 3.3.0
 */
public final class VoteBedrockForms {

    private static final int LEADERBOARD_LIMIT = 20;
    private static final String KEY = "bedrock.";
    private static final String NAV_BACK = KEY + "nav.back";
    private static final String NEWLINE = "\n";
    private static final String PARAM_DAY = "day";
    private static final String PARAM_ITEM = "item";
    private static final String PARAM_COST = "cost";
    private static final String PARAM_REWARD = "reward";

    private final BedrockFormBridge bridge;
    private final VoteService voteService;
    private final VoteFeatures features;
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
    private @Nullable VoteSettingsForm settingsForm;

    @SuppressWarnings("java:S107")
    public VoteBedrockForms(@NotNull BedrockFormBridge bridge,
                            @NotNull VoteService voteService,
                            @NotNull VoteFeatures features,
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
        this.features = features;
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

    public void setSettingsForm(@Nullable VoteSettingsForm settingsForm) {
        this.settingsForm = settingsForm;
    }

    /**
     * Opens the vote settings form.
     *
     * @param player the Bedrock player
     * @return whether the settings form is wired and was sent
     */
    public boolean openSettings(@NotNull Player player) {
        VoteSettingsForm form = settingsForm;
        if (form == null) {
            return false;
        }
        form.open(player);
        return true;
    }

    /**
     * Returns {@code true} when the player connects via Bedrock. Consults the shared login-time
     * {@code BedrockDetectionCache} first, then the call-time Floodgate probe.
     *
     * @param player the player
     * @return whether the player is on Bedrock
     */
    public boolean isBedrock(@NotNull Player player) {
        var cache = R18nManager.getInstance().getBedrockDetectionCache();
        if (cache != null && cache.isBedrockPlayer(player)) {
            return true;
        }
        return bridge.isBedrockPlayer(player);
    }

    // ── Overview ─────────────────────────────────────────────────────────

    public void openOverview(@NotNull Player player) {
        voteService.getPlayerStats(player.getUniqueId())
                .thenCombineAsync(voteService.voteCooldownsSeconds(player.getUniqueId()), (playerStats, cooldowns) -> {
                    List<VoteSite> siteList = new ArrayList<>(voteService.getVoteSites().values());
                    SimpleForm.Builder form = SimpleForm.builder()
                            .title(text(player, KEY + "overview.title"))
                            .content(overviewBody(player, playerStats, siteList, cooldowns));
                    for (VoteSite site : siteList) {
                        form.button(siteButton(player, site, cooldowns.getOrDefault(site.serviceName(), 0L)));
                    }
                    List<Runnable> navigation = addOverviewNavButtons(form, player);
                    form.validResultHandler(response ->
                            handleOverviewClick(player, response.clickedButtonId(), siteList, navigation));
                    return form.build();
                }).thenAccept(form -> bridge.sendForm(player, form));
    }

    private @NotNull String overviewBody(@NotNull Player player, @NotNull VoteSnapshot snapshot,
                                         @NotNull List<VoteSite> sites, @NotNull Map<String, Long> cooldowns) {
        long ready = sites.stream().filter(site -> cooldowns.getOrDefault(site.serviceName(), 0L) <= 0L).count();
        List<String> lines = new ArrayList<>();
        lines.add(text(player, KEY + "overview.intro"));
        lines.add("");
        lines.add(row(player, "votes-total", VoteFormat.number(player, snapshot.totalVotes())));
        lines.add(row(player, "last-vote", VoteFormat.ago(player, snapshot.lastVoteAt())));
        lines.add(row(player, "vote-points", VoteFormat.number(player, snapshot.votePoints())));
        if (features.streaks()) {
            lines.add(row(player, "streak", VoteFormat.days(player, snapshot.currentStreak())));
            lines.add(row(player, "best-streak", VoteFormat.days(player, snapshot.highestStreak())));
        }
        lines.add("");
        if (sites.isEmpty()) {
            lines.add(text(player, KEY + "overview.no-sites"));
        } else {
            lines.add(msg(KEY + "overview.sites-ready")
                    .with("ready", VoteFormat.number(player, ready))
                    .with("total", VoteFormat.number(player, sites.size()))
                    .toPlainString(player));
        }
        return String.join(NEWLINE, lines);
    }

    private @NotNull String siteButton(@NotNull Player player, @NotNull VoteSite site, long seconds) {
        String name = BedrockFormText.plain(site.displayName());
        if (seconds <= 0L) {
            return msg(KEY + "overview.site-ready").with("site", name).toPlainString(player);
        }
        return msg(KEY + "overview.site-cooldown").with("site", name)
                .with("time", VoteFormat.duration(player, seconds)).toPlainString(player);
    }

    private @NotNull List<Runnable> addOverviewNavButtons(@NotNull SimpleForm.Builder form, @NotNull Player player) {
        List<Runnable> actions = new ArrayList<>();
        if (features.streaks()) {
            form.button(text(player, KEY + "nav.streaks"));
            actions.add(() -> openStreaks(player));
        }
        form.button(text(player, KEY + "nav.rewards"));
        actions.add(() -> openRewards(player));
        if (features.leaderboard()) {
            form.button(text(player, KEY + "nav.leaderboard"));
            actions.add(() -> openLeaderboard(player));
        }
        if (features.shop() && shopService != null) {
            form.button(text(player, KEY + "nav.shop"));
            actions.add(() -> openShop(player));
        }
        if (features.party() && partyService != null) {
            form.button(text(player, KEY + "nav.party"));
            actions.add(() -> openParty(player));
        }
        if (settingsForm != null) {
            form.button(text(player, KEY + "nav.settings"));
            actions.add(() -> openSettings(player));
        }
        return actions;
    }

    private void handleOverviewClick(@NotNull Player player, int index, @NotNull List<VoteSite> siteList,
                                     @NotNull List<Runnable> navigation) {
        if (index < siteList.size()) {
            VoteSite clicked = siteList.get(index);
            if (clicked.voteUrl() != null) {
                msg(KEY + "overview.site-link").prefix()
                        .with("site", BedrockFormText.plain(clicked.displayName()))
                        .with("url", clicked.voteUrl())
                        .send(player);
            }
            return;
        }
        int navIndex = index - siteList.size();
        if (navIndex >= 0 && navIndex < navigation.size()) {
            navigation.get(navIndex).run();
        }
    }

    // ── Leaderboard ──────────────────────────────────────────────────────

    public void openLeaderboard(@NotNull Player player) {
        openLeaderboard(player, false);
    }

    private void openLeaderboard(@NotNull Player player, boolean monthly) {
        CompletableFuture<List<VoteSnapshot>> top = monthly
                ? leaderboardService.getMonthlyTop(LEADERBOARD_LIMIT)
                : leaderboardService.getAllTimeTop(LEADERBOARD_LIMIT);
        top.thenAccept(entries -> {
            String mode = monthly ? "monthly" : "all-time";
            StringBuilder body = new StringBuilder(text(player, KEY + "leaderboard.header-" + mode)).append(NEWLINE);
            int rank = 1;
            for (VoteSnapshot entry : entries) {
                String name = entry.playerName() != null ? entry.playerName() : text(player, "vote.unknown-player");
                int votes = monthly ? entry.monthlyVotes() : entry.totalVotes();
                body.append(NEWLINE).append(msg(KEY + "leaderboard.entry")
                        .with("rank", rank)
                        .with("player", name)
                        .with("votes", VoteFormat.number(player, votes))
                        .with("streak", VoteFormat.days(player, entry.currentStreak()))
                        .toPlainString(player));
                rank++;
            }
            if (entries.isEmpty()) {
                body.append(NEWLINE).append(text(player, KEY + "leaderboard.empty"));
            }
            SimpleForm form = SimpleForm.builder()
                    .title(text(player, KEY + "leaderboard.title"))
                    .content(body.toString())
                    .button(text(player, KEY + "leaderboard.show-" + (monthly ? "all-time" : "monthly")))
                    .button(text(player, NAV_BACK))
                    .validResultHandler(response -> {
                        if (response.clickedButtonId() == 0) {
                            openLeaderboard(player, !monthly);
                        } else {
                            openOverview(player);
                        }
                    })
                    .build();
            bridge.sendForm(player, form);
        });
    }

    // ── Streaks ──────────────────────────────────────────────────────────

    public void openStreaks(@NotNull Player player) {
        voteService.getPlayerStats(player.getUniqueId())
                .thenCombineAsync(claimService.getClaimedDays(player.getUniqueId()), (playerStats, claimed) -> {
                    StringBuilder body = new StringBuilder(String.join(NEWLINE,
                            text(player, KEY + "streaks.header"),
                            "",
                            row(player, "streak", VoteFormat.days(player, playerStats.currentStreak())),
                            row(player, "best-streak", VoteFormat.days(player, playerStats.highestStreak())),
                            "",
                            text(player, KEY + "streaks.milestones")));
                    List<Integer> claimableDays = appendMilestones(body, player, playerStats.highestStreak(), claimed);
                    SimpleForm.Builder form = SimpleForm.builder()
                            .title(text(player, KEY + "streaks.title"))
                            .content(body.toString());
                    for (int day : claimableDays) {
                        form.button(msg(KEY + "streaks.claim-button").with(PARAM_DAY, day).toPlainString(player));
                    }
                    form.button(text(player, NAV_BACK));
                    form.validResultHandler(response ->
                            handleStreakClick(player, response.clickedButtonId(), claimableDays));
                    return form.build();
                }).thenAccept(form -> bridge.sendForm(player, form));
    }

    /**
     * Appends one line per milestone. Reached milestones count from the best streak, the same rule the claim
     * service uses, so a player whose streak broke can still claim what they reached.
     */
    private @NotNull List<Integer> appendMilestones(@NotNull StringBuilder body, @NotNull Player player,
                                                    int highest, @NotNull Set<Integer> claimed) {
        Map<Integer, List<AbstractReward>> milestones = new TreeMap<>(rewardConfig.getStreakRewards());
        List<Integer> claimableDays = new ArrayList<>();
        for (int day : milestones.keySet()) {
            String state;
            if (claimed.contains(day)) {
                state = "claimed";
            } else if (highest >= day && rewardService.isManualStreakClaim()) {
                state = "claimable";
                claimableDays.add(day);
            } else if (highest >= day) {
                state = "reached";
            } else {
                state = "locked";
            }
            body.append(NEWLINE).append(msg(KEY + "streaks.status-" + state).with(PARAM_DAY, day).toPlainString(player));
        }
        return claimableDays;
    }

    private void handleStreakClick(@NotNull Player player, int index, @NotNull List<Integer> claimableDays) {
        if (index >= claimableDays.size()) {
            openOverview(player);
            return;
        }
        int day = claimableDays.get(index);
        claimService.claimMilestone(player, day).thenAccept(result -> {
            if (result == StreakClaimService.ClaimResult.SUCCESS) {
                msg("vote_streak.claim.success").with(PARAM_DAY, day).prefix().send(player);
            } else if (result != StreakClaimService.ClaimResult.BUSY) {
                msg("vote_streak.claim.failed").prefix().send(player);
            }
            openStreaks(player);
        });
    }

    // ── Rewards hub ──────────────────────────────────────────────────────

    public void openRewards(@NotNull Player player) {
        CompletableFuture<Integer> pointsFuture = freezeService.getPoints(player.getUniqueId());
        CompletableFuture<Integer> ownedFuture = freezeService.getOwned(player.getUniqueId());
        CompletableFuture<Integer> giftsFuture = giftService.remainingToday(player);
        CompletableFuture.allOf(pointsFuture, ownedFuture, giftsFuture)
                .thenApplyAsync(ignored -> rewardsForm(player,
                        pointsFuture.join(), ownedFuture.join(), giftsFuture.join()))
                .thenAccept(form -> bridge.sendForm(player, form));
    }

    private @NotNull SimpleForm rewardsForm(@NotNull Player player, int points, int owned, int giftsLeft) {
        List<String> lines = new ArrayList<>();
        lines.add(text(player, KEY + "rewards.header"));
        lines.add("");
        lines.add(row(player, "vote-points", VoteFormat.number(player, points)));
        if (features.freezes()) {
            lines.add(row(player, "freezes", VoteFormat.number(player, owned)));
        }
        if (features.gifts()) {
            lines.add(row(player, "gifts-left", VoteFormat.number(player, giftsLeft)));
        }
        if (features.weekendBonus() && multipliers.isActive()) {
            lines.add(row(player, "weekend-bonus", VoteFormat.multiplier(player, multipliers.current())));
        }
        List<String> perVote = everyVoteLines(player);
        if (!perVote.isEmpty()) {
            lines.add("");
            lines.add(text(player, KEY + "rewards.every-vote"));
            lines.addAll(perVote);
        }
        SimpleForm.Builder form = SimpleForm.builder()
                .title(text(player, KEY + "rewards.title"))
                .content(String.join(NEWLINE, lines));
        List<Runnable> actions = new ArrayList<>();
        if (!luckyPrizes().isEmpty()) {
            form.button(text(player, KEY + "rewards.lucky-catalog"));
            actions.add(() -> openLucky(player));
        }
        if (features.freezes()) {
            form.button(msg(KEY + "rewards.buy-freeze-cost")
                    .with(PARAM_COST, VoteFormat.number(player, freezeService.settings().costPoints()))
                    .toPlainString(player));
            actions.add(() -> buyFreeze(player));
        }
        form.button(text(player, NAV_BACK));
        actions.add(() -> openOverview(player));
        form.validResultHandler(response -> runAction(actions, response.clickedButtonId()));
        return form.build();
    }

    private @NotNull List<String> everyVoteLines(@NotNull Player player) {
        List<AbstractReward> perVote = new ArrayList<>(rewardConfig.getDefaultRewards());
        perVote.addAll(rewardConfig.getGuaranteedRewards());
        List<String> lines = new ArrayList<>();
        for (AbstractReward reward : perVote) {
            for (AbstractReward atomic : RewardViewHelper.flatten(reward)) {
                lines.add(msg(KEY + "party.entry").with(PARAM_REWARD, rewardText(player, atomic)).toPlainString(player));
            }
        }
        return lines;
    }

    private @NotNull List<Prize> luckyPrizes() {
        List<Prize> prizes = new ArrayList<>();
        VoteLuckyView.addFrom(prizes, rewardConfig.getDefaultRewards());
        rewardConfig.getStreakRewards().values().forEach(list -> VoteLuckyView.addFrom(prizes, list));
        rewardConfig.getSiteRewards().values().forEach(list -> VoteLuckyView.addFrom(prizes, list));
        VoteLuckyView.addFrom(prizes, rewardConfig.getVotePartyRewards());
        return prizes;
    }

    private static void runAction(@NotNull List<Runnable> actions, int index) {
        if (index >= 0 && index < actions.size()) {
            actions.get(index).run();
        }
    }

    private void buyFreeze(@NotNull Player player) {
        int cost = freezeService.settings().costPoints();
        int max = freezeService.resolveMax(player);
        freezeService.purchase(player).thenAccept(result -> {
            VoteRewardsView.sendFreezeResult(player, result, cost, max);
            openRewards(player);
        });
    }

    // ── Lucky catalog ────────────────────────────────────────────────────

    public void openLucky(@NotNull Player player) {
        sendPrizeForm(player, KEY + "lucky.title", text(player, KEY + "lucky.header"), luckyPrizes(),
                () -> openRewards(player));
    }

    private void sendPrizeForm(@NotNull Player player, @NotNull String titleKey, @NotNull String header,
                               @NotNull List<Prize> prizes, @NotNull Runnable back) {
        StringBuilder body = new StringBuilder(header).append(NEWLINE);
        prizes.stream().sorted(Comparator.comparingDouble(Prize::percent).reversed()).forEach(prize -> {
            long won = prize.id() == null ? 0L : stats.getCount(prize.id());
            body.append(NEWLINE).append(msg(KEY + "prize.entry")
                    .with("chance", VoteFormat.percent(player, prize.percent()))
                    .with(PARAM_REWARD, rewardText(player, prize.reward()))
                    .with("won", VoteFormat.number(player, won))
                    .toPlainString(player));
        });
        if (prizes.isEmpty()) {
            body.append(NEWLINE).append(text(player, KEY + "prize.empty"));
        }
        SimpleForm form = SimpleForm.builder()
                .title(text(player, titleKey))
                .content(body.toString())
                .button(text(player, NAV_BACK))
                .validResultHandler(response -> back.run())
                .build();
        bridge.sendForm(player, form);
    }

    // ── Party catalog ────────────────────────────────────────────────────

    public void openParty(@NotNull Player player) {
        List<Prize> prizes = new ArrayList<>();
        LuckyReward pool = rewardConfig.getVotePartyPool();
        if (pool != null) {
            VoteLuckyView.addPool(prizes, pool);
        }
        StringBuilder header = new StringBuilder(text(player, KEY + "party.header"));
        if (partyService != null) {
            int current = partyService.currentVotes();
            int target = partyService.targetVotes();
            header.append(NEWLINE).append(row(player, "votes", current + " / " + target))
                    .append(NEWLINE).append(row(player, "votes-left", VoteFormat.number(player, Math.max(0, target - current))));
        }
        List<String> fixed = new ArrayList<>();
        for (AbstractReward reward : rewardConfig.getVotePartyRewards()) {
            for (AbstractReward atomic : RewardViewHelper.flatten(reward)) {
                fixed.add(msg(KEY + "party.entry").with(PARAM_REWARD, rewardText(player, atomic)).toPlainString(player));
            }
        }
        if (!fixed.isEmpty()) {
            header.append(NEWLINE).append(NEWLINE).append(text(player, KEY + "party.rewards"))
                    .append(NEWLINE).append(String.join(NEWLINE, fixed));
        }
        header.append(NEWLINE).append(NEWLINE).append(text(player, KEY + "party.pool"));
        sendPrizeForm(player, KEY + "party.title", header.toString(), prizes, () -> openOverview(player));
    }

    // ── Shop ─────────────────────────────────────────────────────────────

    public void openShop(@NotNull Player player) {
        VoteShopService shop = shopService;
        if (shop == null) {
            return;
        }
        shop.getPoints(player.getUniqueId()).thenAccept(points -> {
            List<VoteShopItem> items = new ArrayList<>(shop.items());
            StringBuilder body = new StringBuilder(text(player, KEY + "shop.header"))
                    .append(NEWLINE).append(row(player, "vote-points", VoteFormat.number(player, points)))
                    .append(NEWLINE);
            SimpleForm.Builder form = SimpleForm.builder().title(text(player, KEY + "shop.title"));
            for (VoteShopItem item : items) {
                String stateKey = points >= item.cost() ? "shop.line-affordable" : "shop.line-short";
                body.append(NEWLINE).append(msg(KEY + stateKey)
                        .with(PARAM_ITEM, BedrockFormText.plain(item.name()))
                        .with(PARAM_COST, VoteFormat.number(player, item.cost()))
                        .with(PARAM_REWARD, rewardText(player, item.reward()))
                        .toPlainString(player));
                form.button(msg(KEY + "shop.button")
                        .with(PARAM_ITEM, BedrockFormText.plain(item.name()))
                        .with(PARAM_COST, VoteFormat.number(player, item.cost()))
                        .toPlainString(player));
            }
            form.content(body.toString());
            form.button(text(player, NAV_BACK));
            form.validResultHandler(response -> {
                int index = response.clickedButtonId();
                if (index < items.size()) {
                    openShopConfirm(player, shop, items.get(index));
                } else {
                    openOverview(player);
                }
            });
            bridge.sendForm(player, form.build());
        });
    }

    private void openShopConfirm(@NotNull Player player, @NotNull VoteShopService shop, @NotNull VoteShopItem item) {
        String name = BedrockFormText.plain(item.name());
        ModalForm form = ModalForm.builder()
                .title(text(player, KEY + "shop.confirm-title"))
                .content(msg(KEY + "shop.confirm-body").with(PARAM_ITEM, name)
                        .with(PARAM_COST, VoteFormat.number(player, item.cost())).toPlainString(player))
                .button1(text(player, KEY + "shop.confirm-buy"))
                .button2(text(player, KEY + "shop.confirm-cancel"))
                .validResultHandler(response -> {
                    if (response.clickedButtonId() == 0) {
                        buy(player, shop, item, name);
                    } else {
                        openShop(player);
                    }
                })
                .build();
        bridge.sendForm(player, form);
    }

    private void buy(@NotNull Player player, @NotNull VoteShopService shop, @NotNull VoteShopItem item,
                     @NotNull String name) {
        shop.purchase(player, item).thenAccept(result -> {
            String key = switch (result) {
                case SUCCESS -> KEY + "shop.bought";
                case NOT_ENOUGH_POINTS -> KEY + "shop.not-enough";
                case NO_PROFILE -> "vote_shop.no-profile";
                case GRANT_FAILED -> KEY + "shop.grant-failed";
                case BUSY -> null;
                default -> KEY + "shop.error";
            };
            if (key != null) {
                msg(key).prefix()
                        .with(PARAM_ITEM, name)
                        .with(PARAM_COST, VoteFormat.number(player, item.cost()))
                        .send(player);
            }
            openShop(player);
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static @NotNull String rewardText(@NotNull Player player, @NotNull AbstractReward reward) {
        return BedrockFormText.plain(VoteRewardDescriber.describeText(reward, player));
    }

    private static @NotNull String row(@NotNull Player player, @NotNull String label, @NotNull String value) {
        return msg(KEY + "row")
                .with("label", text(player, "gui.common.label." + label))
                .with("value", value)
                .toPlainString(player);
    }

    private static @NotNull String text(@NotNull Player player, @NotNull String key) {
        return msg(key).toPlainString(player);
    }

    private static @NotNull MessageBuilder msg(@NotNull String key) {
        return R18nManager.getInstance().msg(key);
    }
}
