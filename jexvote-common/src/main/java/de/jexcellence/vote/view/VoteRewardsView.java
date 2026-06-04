package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.jexplatform.view.RewardViewHelper;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.reward.ChanceReward;
import de.jexcellence.vote.reward.LuckyReward;
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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * "Vote Economy" overview — Lucky Vote / chance rewards (with odds + drop
 * counts), weekend-multiplier status, and vote-party progress + the rewards you
 * earn. Fully i18n + the shared gradient palette; text summary mirrors the GUI.
 *
 * @author JExcellence
 */
public final class VoteRewardsView extends VoteBaseView {

    private static final int SLOT_POINTS = 13;
    private static final int SLOT_FREEZE = 30;
    private static final int SLOT_GIFT = 32;
    private static final String TAG_BUY_FREEZE = "buy_freeze";

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

    private VoteOverviewView overviewView;

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

    @Override protected @NotNull String title()          { return "vote_rewards.title"; }
    @Override protected int rows()                        { return 6; }
    @Override protected @NotNull InventoryHolder holder() { return holder; }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {
        glass(inv, Material.LIME_STAINED_GLASS_PANE, 0, 2, 6, 8);
        glass(inv, Material.GREEN_STAINED_GLASS_PANE, 1, 7);
        inv.setItem(4, ItemBuilder.of(Material.EMERALD)
                .name(ic("vote_rewards.header.name", viewer))
                .glow(true)
                .lore(ics("vote_rewards.header.lore", viewer))
                .build());

        inv.setItem(20, luckyIcon(viewer));
        inv.setItem(22, multiplierIcon(viewer));
        inv.setItem(24, partyIcon(viewer));

        // Live owned/remaining/points are filled in asynchronously by
        // refreshLiveCounts(); these are the immediate placeholders.
        inv.setItem(SLOT_POINTS, pointsIcon(viewer, -1));
        inv.setItem(SLOT_FREEZE, freezeIcon(viewer, -1));
        inv.setItem(SLOT_GIFT, giftIcon(viewer, -1));

        if (overviewView != null) {
            inv.setItem(49, backButton());
        }
    }

    @Override
    public void open(@NotNull Player viewer) {
        super.open(viewer);
        refreshLiveCounts(viewer);
    }

    /**
     * Loads the player's owned freeze count and remaining gifts off-thread,
     * then updates the freeze/gift icons on the main thread if the view is
     * still open.
     */
    private void refreshLiveCounts(@NotNull Player viewer) {
        UUID uuid = viewer.getUniqueId();
        CompletableFuture<Integer> ownedFuture = freezeService.getOwned(uuid);
        CompletableFuture<Integer> remainingFuture = giftService.remainingToday(viewer);
        CompletableFuture<Integer> pointsFuture = freezeService.getPoints(uuid);

        CompletableFuture.allOf(ownedFuture, remainingFuture, pointsFuture).thenRun(() ->
                scheduler.runAtEntity(viewer, () -> {
                    Inventory top = viewer.getOpenInventory().getTopInventory();
                    if (top.getHolder() != holder) {
                        return;
                    }
                    top.setItem(SLOT_POINTS, pointsIcon(viewer, pointsFuture.join()));
                    top.setItem(SLOT_FREEZE, freezeIcon(viewer, ownedFuture.join()));
                    top.setItem(SLOT_GIFT, giftIcon(viewer, remainingFuture.join()));
                })).exceptionally(ex -> {
            plugin.getLogger().fine(() -> "Failed to refresh vote reward live counts: " + ex.getMessage());
            return null;
        });
    }

    private @NotNull ItemStack pointsIcon(@NotNull Player viewer, int points) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(msg("vote_rewards.points.balance")
                .with("points", points < 0 ? "…" : String.valueOf(points)).itemComponent(viewer));
        lore.add(Component.empty());
        lore.add(ic("vote_rewards.points.hint", viewer));
        return ItemBuilder.of(Material.NETHER_STAR)
                .name(ic("vote_rewards.points.name", viewer))
                .glow(points > 0)
                .lore(lore)
                .build();
    }

    // ── Section icons ───────────────────────────────────────────────

    private @NotNull ItemStack luckyIcon(@NotNull Player viewer) {
        List<ChanceReward> chances = collect(ChanceReward.class);
        List<LuckyReward> luckies = collect(LuckyReward.class);

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        if (chances.isEmpty() && luckies.isEmpty()) {
            lore.add(ic("vote_rewards.lucky.empty", viewer));
        } else {
            for (ChanceReward cr : chances) {
                lore.add(msg("vote_rewards.lucky.entry")
                        .with("reward", VoteRewardDescriber.describe(cr.getReward()))
                        .with("chance", percent(cr.getChance()))
                        .with("won", String.valueOf(countFor(cr.getId())))
                        .itemComponent(viewer));
            }
            for (LuckyReward lr : luckies) {
                lore.add(msg("vote_rewards.lucky.pool")
                        .with("count", String.valueOf(lr.getEntries().size())).itemComponent(viewer));
                double total = lr.getEntries().stream().mapToDouble(LuckyReward.Entry::weight).sum();
                for (LuckyReward.Entry e : lr.getEntries()) {
                    double chance = total > 0 ? (e.weight() / total) * 100.0 : 0.0;
                    lore.add(msg("vote_rewards.lucky.pool-entry")
                            .with("reward", VoteRewardDescriber.describe(e.reward()))
                            .with("chance", fmt(chance))
                            .with("won", String.valueOf(countFor(e.id())))
                            .itemComponent(viewer));
                }
            }
        }
        lore.add(Component.empty());
        return ItemBuilder.of(Material.SPONGE)
                .name(ic("vote_rewards.lucky.name", viewer))
                .glow(!chances.isEmpty() || !luckies.isEmpty())
                .lore(lore)
                .build();
    }

    private @NotNull ItemStack multiplierIcon(@NotNull Player viewer) {
        boolean active = multipliers.isActive();
        MultiplierService.Settings s = settings();

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(active ? ic("vote_rewards.multiplier.status-active", viewer)
                        : ic("vote_rewards.multiplier.status-inactive", viewer));
        lore.add(msg("vote_rewards.multiplier.current")
                .with("factor", fmt(multipliers.current())).itemComponent(viewer));
        lore.add(msg("vote_rewards.multiplier.weekend")
                .with("factor", fmt(s.weekendFactor())).itemComponent(viewer));
        String days = s.weekendDays().stream()
                .map(d -> d.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH))
                .reduce((a, b) -> a + ", " + b).orElse("—");
        lore.add(msg("vote_rewards.multiplier.days").with("days", days).itemComponent(viewer));
        lore.add(Component.empty());
        return ItemBuilder.of(Material.CLOCK)
                .name(ic("vote_rewards.multiplier.name", viewer))
                .glow(active)
                .lore(lore)
                .build();
    }

    private @NotNull ItemStack partyIcon(@NotNull Player viewer) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        if (party == null) {
            lore.add(ic("vote_rewards.party.disabled", viewer));
        } else {
            int current = party.getCurrentVotes();
            int target = party.getTargetVotes();
            lore.add(msg("vote_rewards.party.progress")
                    .with("current", String.valueOf(current))
                    .with("target", String.valueOf(target)).itemComponent(viewer));
            lore.add(msg("vote_rewards.party.remaining")
                    .with("remaining", String.valueOf(party.getRemainingVotes())).itemComponent(viewer));
            lore.add(lore("  " + progressBar(current, target, 20)));
            lore.add(Component.empty());
            lore.add(ic("vote_rewards.party.rewards-header", viewer));
            List<AbstractReward> rewards = rewardConfig.getVotePartyRewards();
            if (rewards.isEmpty()) {
                lore.add(ic("vote_rewards.party.none", viewer));
            } else {
                for (AbstractReward reward : rewards) {
                    for (AbstractReward atomic : RewardViewHelper.flatten(reward)) {
                        lore.add(msg("vote_rewards.party.reward-entry")
                                .with("reward", VoteRewardDescriber.describe(atomic)).itemComponent(viewer));
                    }
                }
            }
        }
        lore.add(Component.empty());
        return ItemBuilder.of(Material.TOTEM_OF_UNDYING)
                .name(ic("vote_rewards.party.name", viewer))
                .glow(party != null)
                .lore(lore)
                .build();
    }

    private @NotNull ItemStack freezeIcon(@NotNull Player viewer, int owned) {
        VoteConfig.FreezeSettings fs = voteConfig.getFreezeSettings();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());

        if (!fs.enabled()) {
            lore.add(ic("vote_rewards.freeze.disabled", viewer));
            lore.add(Component.empty());
            return ItemBuilder.of(Material.PACKED_ICE)
                    .name(ic("vote_rewards.freeze.name", viewer))
                    .lore(lore)
                    .build();
        }

        lore.add(ic("vote_rewards.freeze.how", viewer));
        int max = freezeService.resolveMax(viewer);
        lore.add(msg("vote_rewards.freeze.owned")
                .with("owned", owned < 0 ? "…" : String.valueOf(owned))
                .with("max", String.valueOf(max)).itemComponent(viewer));
        lore.add(msg("vote_rewards.freeze.cost")
                .with("cost", String.valueOf(fs.costPoints())).itemComponent(viewer));
        lore.add(msg("vote_rewards.freeze.duration")
                .with("duration", String.valueOf(fs.durationHours())).itemComponent(viewer));
        lore.add(Component.empty());
        lore.add(ic("vote_rewards.freeze.buy-hint", viewer));

        ItemStack icon = ItemBuilder.of(Material.BLUE_ICE)
                .name(ic("vote_rewards.freeze.name", viewer))
                .glow(owned > 0)
                .lore(lore)
                .build();
        tag(icon, TAG_BUY_FREEZE);
        return icon;
    }

    private @NotNull ItemStack giftIcon(@NotNull Player viewer, int remaining) {
        VoteConfig.GiftSettings gs = voteConfig.getGiftSettings();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());

        if (!gs.enabled()) {
            lore.add(ic("vote_rewards.gift.disabled", viewer));
            lore.add(Component.empty());
            return ItemBuilder.of(Material.NAME_TAG)
                    .name(ic("vote_rewards.gift.name", viewer))
                    .lore(lore)
                    .build();
        }

        lore.add(ic("vote_rewards.gift.how", viewer));
        int limit = giftService.resolveDailyLimit(viewer);
        lore.add(msg("vote_rewards.gift.limit")
                .with("remaining", remaining < 0 ? "…" : String.valueOf(remaining))
                .with("limit", String.valueOf(limit)).itemComponent(viewer));
        if (gs.requireVoteToday()) {
            lore.add(ic("vote_rewards.gift.require-vote", viewer));
        }
        lore.add(Component.empty());
        lore.add(ic("vote_rewards.gift.usage", viewer));

        return ItemBuilder.of(Material.PLAYER_HEAD)
                .name(ic("vote_rewards.gift.name", viewer))
                .glow(remaining > 0)
                .lore(lore)
                .build();
    }

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        String tag = tagOf(clicked);
        if ("back".equals(tag) && overviewView != null) {
            overviewView.open(viewer);
            return;
        }
        if (TAG_BUY_FREEZE.equals(tag)) {
            buyFreeze(viewer);
        }
    }

    private void buyFreeze(@NotNull Player viewer) {
        int cost = freezeService.settings().costPoints();
        int max = freezeService.resolveMax(viewer);
        freezeService.purchase(viewer).thenAccept(result -> {
            switch (result) {
                case SUCCESS -> msg("vote.freeze.bought").prefix()
                        .with("cost", String.valueOf(cost)).send(viewer);
                case DISABLED -> msg("vote.freeze.disabled").prefix().send(viewer);
                case AT_MAX -> msg("vote.freeze.at_max").prefix()
                        .with("max", String.valueOf(max)).send(viewer);
                case NOT_ENOUGH_POINTS -> msg("vote.freeze.not_enough").prefix()
                        .with("cost", String.valueOf(cost)).send(viewer);
                case NO_PROFILE -> msg("vote.freeze.no_profile").prefix().send(viewer);
                default -> msg("vote.freeze.error").prefix().send(viewer);
            }
            // Refresh the owned count after a purchase attempt.
            scheduler.runAtEntity(viewer, () -> refreshLiveCounts(viewer));
        });
    }

    // ── Text summary (console / chat) ───────────────────────────────

    public void sendTextSummary(@NotNull CommandSender sender) {
        R18nManager r18n = R18nManager.getInstance();
        r18n.msg("vote_rewards.text.header").send(sender);

        for (ChanceReward cr : collect(ChanceReward.class)) {
            r18n.msg("vote_rewards.text.lucky-entry").prefix()
                    .with("reward", VoteRewardDescriber.describe(cr.getReward()))
                    .with("chance", percent(cr.getChance()))
                    .with("won", String.valueOf(countFor(cr.getId())))
                    .send(sender);
        }

        r18n.msg("vote_rewards.text.multiplier").prefix()
                .with("status", multipliers.isActive() ? "active" : "inactive")
                .with("factor", fmt(multipliers.current()))
                .send(sender);

        if (party != null) {
            r18n.msg("vote_rewards.text.party").prefix()
                    .with("current", String.valueOf(party.getCurrentVotes()))
                    .with("target", String.valueOf(party.getTargetVotes()))
                    .with("remaining", String.valueOf(party.getRemainingVotes()))
                    .send(sender);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private long countFor(@Nullable String id) {
        return id == null ? 0L : stats.getCount(id);
    }

    private <T extends AbstractReward> @NotNull List<T> collect(@NotNull Class<T> type) {
        List<T> out = new ArrayList<>();
        addMatching(rewardConfig.getDefaultRewards(), type, out);
        rewardConfig.getSiteRewards().values().forEach(list -> addMatching(list, type, out));
        rewardConfig.getStreakRewards().values().forEach(list -> addMatching(list, type, out));
        addMatching(rewardConfig.getVotePartyRewards(), type, out);
        return out;
    }

    private static <T extends AbstractReward> void addMatching(@NotNull List<AbstractReward> source,
                                                               @NotNull Class<T> type,
                                                               @NotNull List<T> out) {
        for (AbstractReward reward : source) {
            if (type.isInstance(reward)) out.add(type.cast(reward));
        }
    }

    private @NotNull MultiplierService.Settings settings() {
        return new MultiplierService.Settings(
                voteConfig.isWeekendMultiplierEnabled(),
                voteConfig.getWeekendMultiplierFactor(),
                voteConfig.getWeekendMultiplierDays(),
                voteConfig.getWeekendMultiplierTimezone());
    }

    private static @NotNull String percent(double chance) {
        return fmt(chance * 100.0);
    }

    private static @NotNull String fmt(double value) {
        if (value == Math.floor(value)) return String.valueOf((long) value);
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
