package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
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
 * "Vote Economy" overview: surfaces the Lucky Vote / chance rewards, the
 * weekend multiplier status, vote-party progress and reward drop-count stats —
 * the player-facing window onto the Phase A/B/C systems. Text summary mirrors
 * the GUI for console / chat use.
 *
 * @author JExcellence
 * @since 3.5.0
 */
public final class VoteRewardsView extends VoteBaseView {

    private static final String GRADIENT_END = "</bold></gradient>";
    private static final String GOLD = "<gradient:#fde047:#f59e0b><bold>";
    private static final String GREEN = "<gradient:#86efac:#16a34a><bold>";

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
                .name(name(GOLD + "✦ Vote Economy" + GRADIENT_END))
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
            lore.add(lore("  <gray>No lucky rewards configured."));
        } else {
            for (ChanceReward cr : chances) {
                long won = cr.getId() != null ? stats.getCount(cr.getId()) : 0L;
                lore.add(lore("  <dark_gray>▸</dark_gray> <gray>" + describe(cr.getReward())
                        + "</gray> <yellow>" + percent(cr.getChance()) + "%</yellow>"
                        + "  <dark_gray>(" + won + "× won)</dark_gray>"));
            }
            for (LuckyReward lr : luckies) {
                double total = lr.getEntries().stream().mapToDouble(LuckyReward.Entry::weight).sum();
                lore.add(lore("  <dark_gray>▸</dark_gray> <gradient:#fde047:#f59e0b>Jackpot pool</gradient> <dark_gray>(" + lr.getEntries().size() + " outcomes)</dark_gray>"));
                for (LuckyReward.Entry e : lr.getEntries()) {
                    long won = e.id() != null ? stats.getCount(e.id()) : 0L;
                    double chance = total > 0 ? (e.weight() / total) * 100.0 : 0.0;
                    lore.add(lore("     <dark_gray>•</dark_gray> <gray>" + describe(e.reward())
                            + "</gray> <yellow>" + fmt(chance) + "%</yellow>"
                            + "  <dark_gray>(" + won + "×)</dark_gray>"));
                }
            }
        }
        lore.add(Component.empty());
        return ItemBuilder.of(Material.SPONGE)
                .name(name(GOLD + "★ Lucky Vote" + GRADIENT_END))
                .glow(!chances.isEmpty() || !luckies.isEmpty())
                .lore(lore)
                .build();
    }

    private @NotNull ItemStack multiplierIcon(@NotNull Player viewer) {
        boolean active = multipliers.isActive();
        double factor = multipliers.current();
        MultiplierService.Settings s = settingsOrNull();

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(lore("  <dark_gray>▸</dark_gray> <gray>Status:</gray> "
                + (active ? "<gradient:#86efac:#16a34a>ACTIVE</gradient>" : "<dark_gray>inactive</dark_gray>")));
        lore.add(lore("  <dark_gray>▸</dark_gray> <gray>Current:</gray> <yellow>" + fmt(factor) + "×</yellow>"));
        if (s != null) {
            lore.add(lore("  <dark_gray>▸</dark_gray> <gray>Weekend factor:</gray> <yellow>" + fmt(s.weekendFactor()) + "×</yellow>"));
            String days = s.weekendDays().stream()
                    .map(d -> d.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH))
                    .reduce((a, b) -> a + ", " + b).orElse("—");
            lore.add(lore("  <dark_gray>▸</dark_gray> <gray>Days:</gray> <white>" + days + "</white>"));
        }
        lore.add(Component.empty());
        return ItemBuilder.of(Material.CLOCK)
                .name(name(GOLD + "⚡ Weekend Multiplier" + GRADIENT_END))
                .glow(active)
                .lore(lore)
                .build();
    }

    private @NotNull ItemStack partyIcon(@NotNull Player viewer) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        if (party == null) {
            lore.add(lore("  <dark_gray>Vote parties are disabled."));
        } else {
            int current = party.getCurrentVotes();
            int target = party.getTargetVotes();
            lore.add(lore("  <dark_gray>▸</dark_gray> <gray>Progress:</gray> <gradient:#86efac:#16a34a>"
                    + current + "</gradient><dark_gray>/</dark_gray><white>" + target + "</white>"));
            lore.add(lore("  <dark_gray>▸</dark_gray> <gray>Remaining:</gray> <white>" + party.getRemainingVotes() + "</white>"));
            lore.add(lore("  " + progressBar(current, target, 20)));
            lore.add(Component.empty());
            int rewardCount = rewardConfig.getVotePartyRewards().size();
            lore.add(lore("  <dark_gray>" + rewardCount + " party reward" + plural(rewardCount) + " on completion"));
        }
        lore.add(Component.empty());
        return ItemBuilder.of(Material.TOTEM_OF_UNDYING)
                .name(name(GOLD + "✦ Vote Party" + GRADIENT_END))
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

    /**
     * Sends a plain-text equivalent of the GUI, for console or chat use.
     */
    public void sendTextSummary(@NotNull CommandSender sender) {
        R18nManager r18n = R18nManager.getInstance();
        r18n.msg("vote_rewards.text.header").send(sender);

        for (ChanceReward cr : collect(ChanceReward.class)) {
            long won = cr.getId() != null ? stats.getCount(cr.getId()) : 0L;
            r18n.msg("vote_rewards.text.lucky-entry").prefix()
                    .with("reward", describe(cr.getReward()))
                    .with("chance", percent(cr.getChance()))
                    .with("won", String.valueOf(won))
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

    private @Nullable MultiplierService.Settings settingsOrNull() {
        return new MultiplierService.Settings(
                voteConfig.isWeekendMultiplierEnabled(),
                voteConfig.getWeekendMultiplierFactor(),
                voteConfig.getWeekendMultiplierDays(),
                voteConfig.getWeekendMultiplierTimezone());
    }

    /** A short human label for a reward, from its type and estimated value. */
    private static @NotNull String describe(@NotNull AbstractReward reward) {
        double value = reward.estimatedValue();
        String type = reward.typeId();
        if (value > 0) {
            return type + " ×" + (value == Math.floor(value) ? String.valueOf((long) value) : fmt(value));
        }
        return type;
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
