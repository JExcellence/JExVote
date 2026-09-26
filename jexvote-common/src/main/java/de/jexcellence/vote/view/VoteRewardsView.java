package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.view.RewardViewHelper;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.config.VoteConfig;
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
 * Vote rewards ({@code /vote rewards}): the wallet header, then one card each for Lucky Vote, the weekend
 * bonus, the vote party, Streak Freezes, gifting and the shop. Live counts load asynchronously and the view
 * redraws in place. {@link #sendTextSummary} prints the same facts for the console.
 *
 * @author JExcellence
 */
public final class VoteRewardsView extends VoteBaseView {

    private static final String KEY = "vote_rewards.";
    private static final String LABEL = VoteCards.COMMON + "label.";
    private static final String TAG_BUY_FREEZE = "buy_freeze";
    private static final String TAG_OPEN_PARTY = "open_party";
    private static final String TAG_OPEN_SHOP = "open_shop";
    private static final String TAG_OPEN_LUCKY = "open_lucky";
    private static final String PARAM_VALUE = "value";
    private static final String PARAM_COST = "cost";
    private static final String PARAM_MAX = "max";
    private static final String TONE_ACCENT = "accent";
    private static final String DESCRIPTION = ".description";
    private static final int SLOT_LUCKY = 20;
    private static final int SLOT_MULTIPLIER = 22;
    private static final int SLOT_PARTY = 24;
    private static final int SLOT_FREEZE = 29;
    private static final int SLOT_GIFT = 31;
    private static final int SLOT_SHOP = 33;

    private final Holder holder = new Holder();
    private final JavaPlugin plugin;
    private final PlatformScheduler scheduler;
    private final VoteConfig voteConfig;
    private final VoteRewardConfig rewardConfig;
    private final MultiplierService multipliers;
    private final @Nullable VotePartyService party;
    private final RewardStatsService stats;
    private final StreakFreezeService freezeService;
    private final VoteGiftService giftService;
    private final Map<UUID, Wallet> walletByViewer = new ConcurrentHashMap<>();

    private @Nullable VoteOverviewView overviewView;
    private @Nullable VotePartyView partyView;
    private @Nullable VoteShopView shopView;
    private @Nullable VoteLuckyView luckyView;

    /**
     * The viewer's live counts.
     *
     * @param points        vote points
     * @param freezes       owned Streak Freezes
     * @param giftsLeft     gifts left today
     */
    private record Wallet(int points, int freezes, int giftsLeft) {
    }

    @SuppressWarnings("java:S107")
    public VoteRewardsView(@NotNull JavaPlugin plugin,
                           @NotNull VoteConfig voteConfig,
                           @NotNull VoteRewardConfig rewardConfig,
                           @NotNull MultiplierService multipliers,
                           @Nullable VotePartyService party,
                           @NotNull RewardStatsService stats,
                           @NotNull StreakFreezeService freezeService,
                           @NotNull VoteGiftService giftService) {
        this.plugin = plugin;
        this.scheduler = PlatformScheduler.of(plugin);
        this.voteConfig = voteConfig;
        this.rewardConfig = rewardConfig;
        this.multipliers = multipliers;
        this.party = party;
        this.stats = stats;
        this.freezeService = freezeService;
        this.giftService = giftService;
    }

    public void setOverviewView(@NotNull VoteOverviewView view) { this.overviewView = view; }

    public void setPartyView(@NotNull VotePartyView view) { this.partyView = view; }

    public void setShopView(@NotNull VoteShopView view) { this.shopView = view; }

    public void setLuckyView(@NotNull VoteLuckyView view) { this.luckyView = view; }

    @Override protected @NotNull String title() { return KEY + "title"; }
    @Override protected int rows() { return 6; }
    @Override protected @NotNull InventoryHolder holder() { return holder; }

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
        inv.setItem(SLOT_LUCKY, luckyCard(viewer));
        inv.setItem(SLOT_MULTIPLIER, multiplierCard(viewer));
        inv.setItem(SLOT_PARTY, partyCard(viewer));
        inv.setItem(SLOT_FREEZE, freezeCard(viewer, wallet));
        inv.setItem(SLOT_GIFT, giftCard(viewer, wallet));
        if (shopView != null && voteConfig.isFeatureShop()) {
            inv.setItem(SLOT_SHOP, shopCard(viewer));
        }
    }

    // ── Cards ─────────────────────────────────────────────────────────

    private @NotNull ItemStack header(@NotNull Player viewer, @Nullable Wallet wallet) {
        List<Component> rows = new ArrayList<>();
        if (wallet == null) {
            rows.add(VoteCards.rowOf(viewer, LABEL + "vote-points", VoteCards.loading(viewer)));
        } else {
            rows.add(VoteCards.rowOf(viewer, LABEL + "vote-points", VoteCards.points(viewer, wallet.points())));
            if (voteConfig.getFreezeSettings().enabled()) {
                rows.add(VoteCards.rowOf(viewer, LABEL + "freezes",
                        VoteCards.ofTotal(viewer, wallet.freezes(), freezeService.resolveMax(viewer))));
            }
            if (voteConfig.getGiftSettings().enabled()) {
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

    private @NotNull ItemStack luckyCard(@NotNull Player viewer) {
        List<VotePrizeCatalogView.Prize> prizes = new ArrayList<>();
        VoteLuckyView.addFrom(prizes, rewardConfig.getDefaultRewards());
        rewardConfig.getSiteRewards().values().forEach(list -> VoteLuckyView.addFrom(prizes, list));
        rewardConfig.getStreakRewards().values().forEach(list -> VoteLuckyView.addFrom(prizes, list));
        VoteLuckyView.addFrom(prizes, rewardConfig.getVotePartyRewards());
        String base = KEY + "lucky";
        if (prizes.isEmpty()) {
            return disabledCard(viewer, base + ".name-off", base + ".description-off");
        }
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
        if (!voteConfig.isWeekendMultiplierEnabled()) {
            return disabledCard(viewer, base + ".name-off", base + ".description-off");
        }
        boolean active = multipliers.isActive();
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraphOf(viewer, base + DESCRIPTION))
                .section(VoteCards.section(viewer, "bonus"), List.of(
                        VoteCards.rowOf(viewer, LABEL + "now", VoteCards.tone(viewer, active ? "ok" : "plain",
                                VoteFormat.multiplier(viewer, multipliers.current()))),
                        VoteCards.rowOf(viewer, LABEL + "weekend", VoteCards.tone(viewer, TONE_ACCENT,
                                VoteFormat.multiplier(viewer, voteConfig.getWeekendMultiplierFactor()))),
                        VoteCards.rowOf(viewer, LABEL + "days", VoteCards.value(viewer,
                                VoteFormat.dayNames(viewer, voteConfig.getWeekendMultiplierDays())))))
                .block(List.of(VoteCards.ic(viewer, active ? base + ".state-on" : base + ".state-off")));
        appendLoreExtra(lore, KEY + "multiplier", viewer);
        ItemStack card = VoteCards.card(Material.CLOCK,
                VoteCards.ic(viewer, active ? base + ".name-on" : base + ".name-idle"), lore.build());
        return active ? VoteCards.glint(card) : card;
    }

    private @NotNull ItemStack partyCard(@NotNull Player viewer) {
        String base = KEY + "party";
        if (party == null) {
            return disabledCard(viewer, base + ".name-off", base + ".description-off");
        }
        int current = party.getCurrentVotes();
        int target = party.getTargetVotes();
        List<Component> fixed = new ArrayList<>();
        for (AbstractReward reward : rewardConfig.getVotePartyRewards()) {
            for (AbstractReward atomic : RewardViewHelper.flatten(reward)) {
                fixed.add(VoteCards.reward(viewer, VoteRewardDescriber.describe(atomic, viewer)));
            }
        }
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraph(viewer, VoteCards.msg(base + DESCRIPTION)
                        .with("target", VoteFormat.number(viewer, target)).text(viewer)))
                .section(VoteCards.section(viewer, "progress"), List.of(
                        VoteCards.bar(viewer, current, target),
                        VoteCards.rowOf(viewer, LABEL + "votes", VoteCards.ofTotal(viewer, current, target)),
                        VoteCards.rowOf(viewer, LABEL + "votes-left", VoteCards.number(viewer, party.getRemainingVotes()))))
                .section(VoteCards.section(viewer, "every-voter"), fixed);
        if (partyView != null) {
            lore.block(List.of(VoteCards.ic(viewer, base + ".action")));
        }
        appendLoreExtra(lore, base, viewer);
        ItemStack card = VoteCards.card(Material.CAKE, VoteCards.ic(VoteCards.msg(base + ".name")
                .with(PARAM_VALUE, VoteCards.ofTotal(viewer, current, target)), viewer), lore.build());
        if (partyView != null) {
            tag(card, TAG_OPEN_PARTY);
        }
        return card;
    }

    private @NotNull ItemStack freezeCard(@NotNull Player viewer, @Nullable Wallet wallet) {
        VoteConfig.FreezeSettings settings = voteConfig.getFreezeSettings();
        String base = KEY + "freeze";
        if (!settings.enabled()) {
            return disabledCard(viewer, base + ".name-off", base + ".description-off");
        }
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
        String name = wallet == null ? VoteCards.loading(viewer) : VoteCards.ofTotal(viewer, wallet.freezes(), max);
        ItemStack card = VoteCards.card(Material.BLUE_ICE,
                VoteCards.ic(VoteCards.msg(base + ".name").with(PARAM_VALUE, name), viewer), lore.build());
        tag(card, TAG_BUY_FREEZE);
        return card;
    }

    private @NotNull Component freezeState(@NotNull Player viewer, @Nullable Wallet wallet, int max, int cost) {
        String base = KEY + "freeze.";
        if (wallet != null && wallet.freezes() >= max) {
            return VoteCards.ic(viewer, base + "state-full");
        }
        if (wallet != null && wallet.points() < cost) {
            return VoteCards.ic(VoteCards.msg(base + "state-short")
                    .with(PARAM_VALUE, VoteFormat.number(viewer, (long) cost - wallet.points())), viewer);
        }
        return VoteCards.ic(viewer, base + "action");
    }

    private @NotNull ItemStack giftCard(@NotNull Player viewer, @Nullable Wallet wallet) {
        VoteConfig.GiftSettings settings = voteConfig.getGiftSettings();
        String base = KEY + "gift";
        if (!settings.enabled()) {
            return disabledCard(viewer, base + ".name-off", base + ".description-off");
        }
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

    private @NotNull ItemStack shopCard(@NotNull Player viewer) {
        String base = KEY + "shop";
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraphOf(viewer, base + DESCRIPTION))
                .block(List.of(VoteCards.ic(viewer, VoteCards.COMMON + "action.open")));
        appendLoreExtra(lore, base, viewer);
        ItemStack card = VoteCards.card(Material.EMERALD, VoteCards.ic(viewer, base + ".name"), lore.build());
        tag(card, TAG_OPEN_SHOP);
        return card;
    }

    /** A switched-off feature: red dye, same name family, one sentence why. */
    private @NotNull ItemStack disabledCard(@NotNull Player viewer, @NotNull String nameKey,
                                            @NotNull String descriptionKey) {
        return VoteCards.card(Material.RED_DYE, VoteCards.ic(viewer, nameKey),
                CardLore.create().block(VoteCards.paragraphOf(viewer, descriptionKey)).build());
    }

    // ── Clicks ────────────────────────────────────────────────────────

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        String tag = tagOf(clicked);
        if (tag == null) {
            return;
        }
        VoteBaseView target = switch (tag) {
            case TAG_BACK -> overviewView;
            case TAG_OPEN_PARTY -> partyView;
            case TAG_OPEN_LUCKY -> luckyView;
            case TAG_OPEN_SHOP -> shopView;
            default -> null;
        };
        if (target != null) {
            target.open(viewer);
        } else if (TAG_BUY_FREEZE.equals(tag)) {
            buyFreeze(viewer);
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
     * Prints the reward facts as a chat panel: header, then one row per chance reward, the multiplier and the
     * party progress.
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
        r18n.msg(KEY + "text.multiplier")
                .with("status", r18n.msg(multipliers.isActive() ? KEY + "text.active" : KEY + "text.inactive")
                        .text(viewer))
                .with("factor", VoteFormat.multiplier(viewer, multipliers.current()))
                .send(sender);
        if (party != null) {
            r18n.msg(KEY + "text.party")
                    .with("current", VoteFormat.number(viewer, party.getCurrentVotes()))
                    .with("target", VoteFormat.number(viewer, party.getTargetVotes()))
                    .with("remaining", VoteFormat.number(viewer, party.getRemainingVotes()))
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
