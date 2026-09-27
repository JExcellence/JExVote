package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.view.RewardViewHelper;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.config.VoteFeatures;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.reward.ChanceReward;
import de.jexcellence.vote.service.MultiplierService;
import de.jexcellence.vote.service.RewardStatsService;
import de.jexcellence.vote.service.StreakFreezeService;
import de.jexcellence.vote.service.VoteGiftService;
import de.jexcellence.vote.service.VotePartyService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vote rewards ({@code /vote rewards}): what a vote pays and what vote points do. The header is the wallet;
 * below it one card each for what every vote pays, Lucky Vote, the weekend bonus, Streak Freezes and gifts.
 * A card only appears when its feature is on, and the cards re-centre. Live counts load asynchronously and
 * the view redraws in place. {@link #sendTextSummary} prints the same facts for the console.
 *
 * @author JExcellence
 */
public final class VoteRewardsView extends VoteBaseView {

    private static final String KEY = "vote_rewards.";
    private static final String LABEL = VoteCards.COMMON + "label.";
    private static final String TAG_BUY_FREEZE = "buy_freeze";
    private static final String TAG_OPEN_LUCKY = "open_lucky";
    private static final String PARAM_VALUE = "value";
    private static final String PARAM_COST = "cost";
    private static final String PARAM_MAX = "max";
    private static final String TONE_ACCENT = "accent";
    private static final String DESCRIPTION = ".description";

    private final Holder holder = new Holder();
    private final JavaPlugin plugin;
    private final PlatformScheduler scheduler;
    private final VoteFeatures features;
    private final VoteRewardConfig rewardConfig;
    private final MultiplierService multipliers;
    private final RewardStatsService stats;
    private final StreakFreezeService freezeService;
    private final VoteGiftService giftService;
    private final Map<UUID, Wallet> walletByViewer = new ConcurrentHashMap<>();

    private @Nullable VotePartyService party;
    private @Nullable VoteOverviewView overviewView;
    private @Nullable VoteLuckyView luckyView;

    /**
     * The viewer's live counts.
     *
     * @param points    vote points
     * @param freezes   owned Streak Freezes
     * @param giftsLeft gifts left today
     */
    private record Wallet(int points, int freezes, int giftsLeft) {
    }

    public VoteRewardsView(@NotNull JavaPlugin plugin,
                           @NotNull VoteFeatures features,
                           @NotNull VoteRewardConfig rewardConfig,
                           @NotNull MultiplierService multipliers,
                           @NotNull RewardStatsService stats,
                           @NotNull StreakFreezeService freezeService,
                           @NotNull VoteGiftService giftService) {
        this.plugin = plugin;
        this.scheduler = PlatformScheduler.of(plugin);
        this.features = features;
        this.rewardConfig = rewardConfig;
        this.multipliers = multipliers;
        this.stats = stats;
        this.freezeService = freezeService;
        this.giftService = giftService;
    }

    public void setParty(@Nullable VotePartyService party) { this.party = party; }

    public void setOverviewView(@NotNull VoteOverviewView view) { this.overviewView = view; }

    public void setLuckyView(@NotNull VoteLuckyView view) { this.luckyView = view; }

    @Override protected @NotNull String title() { return KEY + "title"; }
    @Override protected int rows() { return 6; }
    @Override protected @NotNull InventoryHolder holder() { return holder; }

    @Override
    protected void forget(@NotNull UUID viewer) {
        walletByViewer.remove(viewer);
    }

    @Override
    public void open(@NotNull Player viewer) {
        walletByViewer.remove(viewer.getUniqueId());
        super.open(viewer);
        refreshWallet(viewer);
    }

    private void refreshWallet(@NotNull Player viewer) {
        UUID uuid = viewer.getUniqueId();
        CompletableFuture<Integer> points = freezeService.getPoints(uuid);
        CompletableFuture<Integer> owned = freezeService.getOwned(uuid);
        CompletableFuture<Integer> gifts = giftService.remainingToday(viewer);
        CompletableFuture.allOf(points, owned, gifts).thenRun(() -> scheduler.runAtEntity(viewer, () -> {
            walletByViewer.put(uuid, new Wallet(points.join(), owned.join(), gifts.join()));
            if (isViewing(viewer)) {
                rerender(viewer);
            }
        })).exceptionally(ex -> {
            plugin.getLogger().fine(() -> "Failed to refresh vote reward live counts: " + ex.getMessage());
            return null;
        });
    }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {
        Wallet wallet = walletByViewer.get(viewer.getUniqueId());
        navBar(inv, viewer, overviewView == null ? null : KEY + "back");
        inv.setItem(SLOT_HEADER, header(viewer, wallet));
        List<ItemStack> cards = new ArrayList<>();
        cards.add(everyVoteCard(viewer));
        List<VotePrizeCatalogView.Prize> prizes = luckyPrizes();
        if (!prizes.isEmpty()) {
            cards.add(luckyCard(viewer, prizes));
        }
        if (features.weekendBonus()) {
            cards.add(multiplierCard(viewer));
        }
        if (features.freezes()) {
            cards.add(freezeCard(viewer, wallet));
        }
        if (features.gifts()) {
            cards.add(giftCard(viewer, wallet));
        }
        int[] slots = VoteLayout.hub(cards.size());
        for (int i = 0; i < slots.length; i++) {
            inv.setItem(slots[i], cards.get(i));
        }
    }

    // ── Cards ─────────────────────────────────────────────────────────

    private @NotNull ItemStack header(@NotNull Player viewer, @Nullable Wallet wallet) {
        List<Component> rows = new ArrayList<>();
        if (wallet == null) {
            rows.add(VoteCards.rowOf(viewer, LABEL + "vote-points", VoteCards.loading(viewer)));
        } else {
            rows.add(VoteCards.rowOf(viewer, LABEL + "vote-points", VoteCards.points(viewer, wallet.points())));
            if (features.freezes()) {
                rows.add(VoteCards.rowOf(viewer, LABEL + "freezes",
                        VoteCards.ofTotal(viewer, wallet.freezes(), freezeService.resolveMax(viewer))));
            }
            if (features.gifts()) {
                rows.add(VoteCards.rowOf(viewer, LABEL + "gifts-left",
                        VoteCards.ofTotal(viewer, wallet.giftsLeft(), giftService.resolveDailyLimit(viewer))));
            }
        }
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraphOf(viewer, KEY + "header.description"))
                .section(VoteCards.section(viewer, "wallet"), rows);
        appendLoreExtra(lore, KEY + "header", viewer);
        String points = wallet == null ? VoteCards.loading(viewer) : VoteCards.points(viewer, wallet.points());
        return VoteCards.card(Material.NETHER_STAR,
                VoteCards.ic(VoteCards.msg(KEY + "header.name").with(PARAM_VALUE, points), viewer), lore.build());
    }

    private @NotNull ItemStack everyVoteCard(@NotNull Player viewer) {
        String base = KEY + "every-vote";
        List<Component> lines = new ArrayList<>();
        List<AbstractReward> perVote = new ArrayList<>(rewardConfig.getDefaultRewards());
        perVote.addAll(rewardConfig.getGuaranteedRewards());
        for (AbstractReward reward : perVote) {
            for (AbstractReward atomic : RewardViewHelper.flatten(reward)) {
                lines.add(VoteCards.reward(viewer, VoteRewardDescriber.describe(atomic, viewer)));
            }
        }
        CardLore lore = CardLore.create().block(VoteCards.paragraphOf(viewer,
                lines.isEmpty() ? base + ".description-points" : base + DESCRIPTION));
        if (!lines.isEmpty()) {
            lore.section(VoteCards.section(viewer, "every-vote"), lines);
        }
        if (!rewardConfig.getSiteRewards().isEmpty()) {
            lore.block(VoteCards.paragraphOf(viewer, base + ".site-note"));
        }
        appendLoreExtra(lore, base, viewer);
        return VoteCards.card(Material.CHEST, VoteCards.ic(viewer, base + ".name"), lore.build());
    }

    private @NotNull List<VotePrizeCatalogView.Prize> luckyPrizes() {
        List<VotePrizeCatalogView.Prize> prizes = new ArrayList<>();
        VoteLuckyView.addFrom(prizes, rewardConfig.getDefaultRewards());
        rewardConfig.getSiteRewards().values().forEach(list -> VoteLuckyView.addFrom(prizes, list));
        rewardConfig.getStreakRewards().values().forEach(list -> VoteLuckyView.addFrom(prizes, list));
        VoteLuckyView.addFrom(prizes, rewardConfig.getVotePartyRewards());
        return prizes;
    }

    private @NotNull ItemStack luckyCard(@NotNull Player viewer, @NotNull List<VotePrizeCatalogView.Prize> prizes) {
        String base = KEY + "lucky";
        double rarest = prizes.stream().mapToDouble(VotePrizeCatalogView.Prize::percent).min().orElse(0.0);
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraphOf(viewer, base + DESCRIPTION))
                .section(VoteCards.section(viewer, "pool"), List.of(
                        VoteCards.rowOf(viewer, LABEL + "prizes", VoteCards.number(viewer, prizes.size())),
                        VoteCards.rowOf(viewer, LABEL + "rarest", VoteCards.value(viewer,
                                VotePrizeCatalogView.percentText(viewer, rarest)))));
        if (luckyView != null) {
            lore.block(List.of(VoteCards.ic(viewer, base + ".action")));
        }
        appendLoreExtra(lore, base, viewer);
        ItemStack card = VoteCards.card(Material.RABBIT_FOOT, VoteCards.ic(VoteCards.msg(base + ".name")
                .with(PARAM_VALUE, VoteFormat.number(viewer, prizes.size())), viewer), lore.build());
        if (luckyView != null) {
            tag(card, TAG_OPEN_LUCKY);
        }
        return card;
    }

    private @NotNull ItemStack multiplierCard(@NotNull Player viewer) {
        String base = KEY + "multiplier";
        MultiplierService.Settings settings = multipliers.settings();
        boolean active = multipliers.isActive();
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraphOf(viewer, base + ".description-scope"))
                .section(VoteCards.section(viewer, "bonus"), List.of(
                        VoteCards.rowOf(viewer, LABEL + "now", VoteCards.tone(viewer, active ? "ok" : "plain",
                                VoteFormat.multiplier(viewer, multipliers.current()))),
                        VoteCards.rowOf(viewer, LABEL + "weekend", VoteCards.tone(viewer, TONE_ACCENT,
                                VoteFormat.multiplier(viewer, settings.weekendFactor()))),
                        VoteCards.rowOf(viewer, LABEL + "days", VoteCards.value(viewer,
                                VoteFormat.dayNames(viewer, settings.weekendDays())))))
                .block(List.of(VoteCards.ic(viewer, active ? base + ".status-on" : base + ".status-off")));
        appendLoreExtra(lore, base, viewer);
        ItemStack card = VoteCards.card(Material.CLOCK,
                VoteCards.ic(viewer, active ? base + ".name-on" : base + ".name-idle"), lore.build());
        return active ? VoteCards.glint(card) : card;
    }

    private @NotNull ItemStack freezeCard(@NotNull Player viewer, @Nullable Wallet wallet) {
        VoteConfig.FreezeSettings settings = freezeService.settings();
        String base = KEY + "freeze";
        int max = freezeService.resolveMax(viewer);
        String owned = wallet == null ? VoteCards.loading(viewer) : VoteCards.ofTotal(viewer, wallet.freezes(), max);
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraphOf(viewer, base + DESCRIPTION))
                .section(VoteCards.section(viewer, "freeze"), List.of(
                        VoteCards.rowOf(viewer, LABEL + "owned", owned),
                        VoteCards.rowOf(viewer, LABEL + "price", VoteCards.points(viewer, settings.costPoints())),
                        VoteCards.rowOf(viewer, LABEL + "covers", VoteCards.value(viewer,
                                VoteFormat.duration(viewer, settings.durationHours() * 3600L)))))
                .block(List.of(freezeState(viewer, wallet, max, settings.costPoints())));
        appendLoreExtra(lore, base, viewer);
        ItemStack card = VoteCards.card(Material.BLUE_ICE,
                VoteCards.ic(VoteCards.msg(base + ".name").with(PARAM_VALUE, owned), viewer), lore.build());
        tag(card, TAG_BUY_FREEZE);
        return card;
    }

    private @NotNull Component freezeState(@NotNull Player viewer, @Nullable Wallet wallet, int max, int cost) {
        String base = KEY + "freeze.";
        if (wallet != null && wallet.freezes() >= max) {
            return VoteCards.ic(viewer, base + "status-full");
        }
        if (wallet != null && wallet.points() < cost) {
            return VoteCards.ic(VoteCards.msg(base + "status-short")
                    .with(PARAM_VALUE, VoteFormat.number(viewer, (long) cost - wallet.points())), viewer);
        }
        return VoteCards.ic(viewer, base + "action");
    }

    private @NotNull ItemStack giftCard(@NotNull Player viewer, @Nullable Wallet wallet) {
        VoteConfig.GiftSettings settings = giftService.settings();
        String base = KEY + "gift";
        int limit = giftService.resolveDailyLimit(viewer);
        String left = wallet == null ? VoteCards.loading(viewer) : VoteCards.ofTotal(viewer, wallet.giftsLeft(), limit);
        List<Component> rows = new ArrayList<>();
        rows.add(VoteCards.rowOf(viewer, LABEL + "gifts-left", left));
        if (settings.requireVoteToday()) {
            rows.add(VoteCards.rowOf(viewer, LABEL + "needs", VoteCards.value(viewer,
                    VoteCards.text(viewer, base + ".needs-vote"))));
        }
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraphOf(viewer, base + DESCRIPTION))
                .section(VoteCards.section(viewer, "today"), rows)
                .block(List.of(VoteCards.ic(viewer, base + ".action")));
        appendLoreExtra(lore, base, viewer);
        return VoteCards.card(Material.NAME_TAG,
                VoteCards.ic(VoteCards.msg(base + ".name").with(PARAM_VALUE, left), viewer), lore.build());
    }

    // ── Clicks ────────────────────────────────────────────────────────

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        String tag = tagOf(clicked);
        if (tag == null) {
            return;
        }
        switch (tag) {
            case TAG_BACK -> {
                if (overviewView != null) {
                    overviewView.open(viewer);
                }
            }
            case TAG_OPEN_LUCKY -> {
                if (luckyView != null) {
                    luckyView.open(viewer);
                }
            }
            case TAG_BUY_FREEZE -> buyFreeze(viewer);
            default -> {
                // Other cards are information only.
            }
        }
    }

    private void buyFreeze(@NotNull Player viewer) {
        int cost = freezeService.settings().costPoints();
        int max = freezeService.resolveMax(viewer);
        freezeService.purchase(viewer).thenAccept(result -> {
            sendFreezeResult(viewer, result, cost, max);
            scheduler.runAtEntity(viewer, () -> refreshWallet(viewer));
        });
    }

    /**
     * Sends the chat line for a Streak Freeze purchase. Shared by the command, this view and the Bedrock form.
     *
     * @param player the buyer
     * @param result the purchase result
     * @param cost   the price in vote points
     * @param max    the buyer's freeze cap
     */
    public static void sendFreezeResult(@NotNull Player player, @NotNull StreakFreezeService.PurchaseResult result,
                                        int cost, int max) {
        R18nManager r18n = R18nManager.getInstance();
        switch (result) {
            case SUCCESS -> r18n.msg("vote.freeze.bought").with(PARAM_COST, cost).prefix().send(player);
            case DISABLED -> r18n.msg("vote.freeze.disabled").prefix().send(player);
            case AT_MAX -> r18n.msg("vote.freeze.at_max").with(PARAM_MAX, max).prefix().send(player);
            case NOT_ENOUGH_POINTS -> r18n.msg("vote.freeze.not_enough").with(PARAM_COST, cost).prefix().send(player);
            case NO_PROFILE -> r18n.msg("vote.freeze.no_profile").prefix().send(player);
            default -> r18n.msg("vote.freeze.error").prefix().send(player);
        }
    }

    // ── Console summary ───────────────────────────────────────────────

    /**
     * Prints the reward facts as a chat panel: header, then one row per chance reward, the weekend bonus and
     * the party progress, each only when the feature is on.
     *
     * @param sender who receives it
     */
    public void sendTextSummary(@NotNull CommandSender sender) {
        Player viewer = sender instanceof Player player ? player : null;
        R18nManager r18n = R18nManager.getInstance();
        r18n.msg(KEY + "text.header").send(sender);
        for (VotePrizeCatalogView.Prize prize : chancePrizes()) {
            r18n.msg(KEY + "text.lucky-entry")
                    .with("reward", VoteRewardDescriber.describe(prize.reward(), viewer))
                    .with("chance", VotePrizeCatalogView.percentText(viewer, prize.percent()))
                    .with("won", VoteFormat.number(viewer, prize.id() == null ? 0L : stats.getCount(prize.id())))
                    .send(sender);
        }
        if (features.weekendBonus()) {
            r18n.msg(KEY + "text.multiplier")
                    .with("status", r18n.msg(multipliers.isActive() ? KEY + "text.active" : KEY + "text.inactive")
                            .text(viewer))
                    .with("factor", VoteFormat.multiplier(viewer, multipliers.current()))
                    .send(sender);
        }
        VotePartyService activeParty = party;
        if (activeParty != null && features.party()) {
            r18n.msg(KEY + "text.party")
                    .with("current", VoteFormat.number(viewer, activeParty.getCurrentVotes()))
                    .with("target", VoteFormat.number(viewer, activeParty.getTargetVotes()))
                    .with("remaining", VoteFormat.number(viewer, activeParty.getRemainingVotes()))
                    .send(sender);
        }
    }

    private @NotNull List<VotePrizeCatalogView.Prize> chancePrizes() {
        List<VotePrizeCatalogView.Prize> prizes = new ArrayList<>();
        List<List<AbstractReward>> lists = new ArrayList<>();
        lists.add(rewardConfig.getDefaultRewards());
        lists.addAll(rewardConfig.getSiteRewards().values());
        lists.addAll(rewardConfig.getStreakRewards().values());
        lists.add(rewardConfig.getVotePartyRewards());
        for (List<AbstractReward> list : lists) {
            for (AbstractReward reward : list) {
                if (reward instanceof ChanceReward chance) {
                    prizes.add(new VotePrizeCatalogView.Prize(chance.getId(), chance.getChance() * 100.0,
                            chance.getReward()));
                }
            }
        }
        return prizes;
    }

    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
