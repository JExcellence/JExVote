package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.jexplatform.view.RewardViewHelper;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.reward.ChanceReward;
import de.jexcellence.vote.reward.LuckyReward;
import de.jexcellence.vote.service.MultiplierService;
import de.jexcellence.vote.service.RewardStatsService;
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

/**
 * "Vote Economy" overview — Lucky Vote / chance rewards (with odds + drop
 * counts), weekend-multiplier status, and vote-party progress + the rewards you
 * earn. Fully i18n + the shared gradient palette; text summary mirrors the GUI.
 *
 * @author JExcellence
 */
public final class VoteRewardsView extends VoteBaseView {

    private final Holder holder = new Holder();
    private final VoteConfig voteConfig;
    private final VoteRewardConfig rewardConfig;
    private final MultiplierService multipliers;
    private final @Nullable VotePartyService party;
    private final RewardStatsService stats;

    private VoteOverviewView overviewView;

    @SuppressWarnings("java:S107")
    public VoteRewardsView(@NotNull JavaPlugin plugin,
                           @NotNull VoteConfig voteConfig,
                           @NotNull VoteRewardConfig rewardConfig,
                           @NotNull MultiplierService multipliers,
                           @Nullable VotePartyService party,
                           @NotNull RewardStatsService stats) {
        this.voteConfig = voteConfig;
        this.rewardConfig = rewardConfig;
        this.multipliers = multipliers;
        this.party = party;
        this.stats = stats;
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

        if (overviewView != null) {
            inv.setItem(49, backButton());
        }
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
                        .with("reward", RewardViewHelper.describe(cr.getReward()))
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
                            .with("reward", RewardViewHelper.describe(e.reward()))
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
                                .with("reward", RewardViewHelper.describe(atomic)).itemComponent(viewer));
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

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        if ("back".equals(tagOf(clicked)) && overviewView != null) {
            overviewView.open(viewer);
        }
    }

    // ── Text summary (console / chat) ───────────────────────────────

    public void sendTextSummary(@NotNull CommandSender sender) {
        R18nManager r18n = R18nManager.getInstance();
        r18n.msg("vote_rewards.text.header").send(sender);

        for (ChanceReward cr : collect(ChanceReward.class)) {
            r18n.msg("vote_rewards.text.lucky-entry").prefix()
                    .with("reward", RewardViewHelper.describe(cr.getReward()))
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
