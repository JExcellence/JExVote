package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.vote.service.VoteRewardService;
import de.jexcellence.vote.service.VoteService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Streak milestone view showing current streak progress and
 * unlockable rewards at each milestone day.
 */
public class VoteStreakView extends VoteBaseView {

    private final Holder holder = new Holder();
    private final VoteService voteService;
    private final VoteRewardService rewardService;
    private final PlatformScheduler scheduler;

    private VoteOverviewView overviewView;

    public VoteStreakView(@NotNull JavaPlugin plugin,
                          @NotNull VoteService voteService,
                          @NotNull VoteRewardService rewardService) {
        this.voteService = voteService;
        this.rewardService = rewardService;
        this.scheduler = PlatformScheduler.of(plugin);
    }

    public void setOverviewView(@NotNull VoteOverviewView view) { this.overviewView = view; }

    @Override protected @NotNull String title()           { return "vote_streak.title"; }
    @Override protected int rows()                         { return 6; }
    @Override protected @NotNull InventoryHolder holder()  { return holder; }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {

        // ── Row 0: Header ───────────────────────────────────────
        glass(inv, Material.ORANGE_STAINED_GLASS_PANE, 0, 2, 6, 8);
        glass(inv, Material.YELLOW_STAINED_GLASS_PANE, 1, 7);
        inv.setItem(4, ItemBuilder.of(Material.MAGMA_CREAM)
                .name(name("<gradient:#fca5a5:#dc2626><bold>🔥 Streak Rewards</bold></gradient>"))
                .glow(true)
                .lore(List.of(
                        Component.empty(),
                        lore("  <gray>Vote daily to build your streak!"),
                        lore("  <gray>Reach milestones for bonus rewards."),
                        Component.empty()))
                .build());

        // ── Row 1: Info items (placeholders) ────────────────────
        glass(inv, Material.YELLOW_STAINED_GLASS_PANE, 9, 17);

        inv.setItem(11, ItemBuilder.of(Material.BLAZE_POWDER)
                .name(name("<gradient:#fde047:#f59e0b><bold>🔥 Your Streak</bold></gradient>"))
                .lore(List.of(Component.empty(), lore("  <gray>Loading..."), Component.empty()))
                .build());

        inv.setItem(13, ItemBuilder.of(Material.BOOK)
                .name(name("<gradient:#86efac:#16a34a><bold>How It Works</bold></gradient>"))
                .lore(List.of(
                        Component.empty(),
                        lore("  <gray>Vote every day to build"),
                        lore("  <gray>your streak. Reach milestones"),
                        lore("  <gray>to earn bonus rewards!"),
                        Component.empty(),
                        lore("  <gradient:#fca5a5:#dc2626>Missing a day resets"),
                        lore("  <gradient:#fca5a5:#dc2626>your streak!"),
                        Component.empty()))
                .build());

        inv.setItem(15, ItemBuilder.of(Material.EXPERIENCE_BOTTLE)
                .name(name("<gradient:#a5f3fc:#06b6d4><bold>Progress</bold></gradient>"))
                .lore(List.of(Component.empty(), lore("  <gray>Loading..."), Component.empty()))
                .build());

        // ── Row 2: Separator ────────────────────────────────────
        glass(inv, Material.ORANGE_STAINED_GLASS_PANE, 18, 22, 26);

        // ── Row 3–4: Milestone grid edges ───────────────────────
        glass(inv, Material.ORANGE_STAINED_GLASS_PANE, 27, 35, 36, 44);

        // ── Row 5: Bottom bar + back button ─────────────────────
        glass(inv, Material.YELLOW_STAINED_GLASS_PANE, 46, 52);
        glass(inv, Material.ORANGE_STAINED_GLASS_PANE, 47, 53);
        inv.setItem(45, backButton());

        // ── Async: load stats & milestones ──────────────────────
        voteService.getPlayerStats(viewer.getUniqueId()).thenAccept(stats ->
                scheduler.runAtEntity(viewer, () -> {
                    Inventory top = viewer.getOpenInventory().getTopInventory();
                    if (top.getHolder() != holder) return;

                    int streak = stats.currentStreak();
                    int highest = stats.highestStreak();

                    Map<Integer, List<AbstractReward>> milestones =
                            new TreeMap<>(rewardService.getStreakRewards());
                    int nextMs = findNextMilestone(streak, milestones);
                    String bar = progressBar(streak, nextMs, 20);

                    renderStreakInfo(top, streak, highest);
                    renderRewardsSummary(top, streak, milestones);
                    renderProgress(top, streak, nextMs, bar);
                    renderMilestoneGrid(top, streak, nextMs, milestones);
                }));
    }

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        String id = tagOf(clicked);
        if ("back".equals(id) && overviewView != null) {
            overviewView.open(viewer);
        }
    }

    // ── Render helpers ──────────────────────────────────────────

    private void renderStreakInfo(@NotNull Inventory inv, int streak, int highest) {
        inv.setItem(11, ItemBuilder.of(Material.BLAZE_POWDER)
                .name(name("<gradient:#fde047:#f59e0b><bold>🔥 Your Streak</bold></gradient>"))
                .glow(streak >= 7)
                .lore(List.of(
                        Component.empty(),
                        lore("  <dark_gray>▸</dark_gray> <gray>Current:</gray> <gradient:#86efac:#16a34a><bold>" + streak + "</bold></gradient> <gray>day" + plural(streak)),
                        lore("  <dark_gray>▸</dark_gray> <gray>Highest:</gray> <gradient:#fde047:#f59e0b><bold>" + highest + "</bold></gradient> <gray>day" + plural(highest)),
                        Component.empty(),
                        lore("  <gray>Vote daily to keep it going!"),
                        Component.empty()))
                .build());
    }

    private void renderRewardsSummary(@NotNull Inventory inv, int streak,
                                       @NotNull Map<Integer, List<AbstractReward>> milestones) {
        int unlocked = countMatching(streak, milestones, true);
        int locked = countMatching(streak, milestones, false);

        inv.setItem(13, ItemBuilder.of(Material.CHEST)
                .name(name("<gradient:#fde047:#f59e0b><bold>Rewards</bold></gradient>"))
                .lore(List.of(
                        Component.empty(),
                        lore("  <gray>Milestones:</gray> <white>" + milestones.size()),
                        lore("  <gray>Unlocked:</gray> <gradient:#86efac:#16a34a>" + unlocked),
                        lore("  <gray>Remaining:</gray> <gradient:#fca5a5:#dc2626>" + locked),
                        Component.empty()))
                .build());
    }

    private void renderProgress(@NotNull Inventory inv, int streak, int nextMs,
                                 @NotNull String bar) {
        inv.setItem(15, ItemBuilder.of(Material.EXPERIENCE_BOTTLE)
                .name(name("<gradient:#a5f3fc:#06b6d4><bold>Progress</bold></gradient>"))
                .lore(List.of(
                        Component.empty(),
                        lore("  <gray>Next milestone:</gray> <white>Day " + nextMs),
                        Component.empty(),
                        lore("  " + bar),
                        lore("  <gradient:#86efac:#16a34a>" + streak + "</gradient> <dark_gray>/</dark_gray> <white>" + nextMs),
                        Component.empty()))
                .build());
    }

    private void renderMilestoneGrid(@NotNull Inventory inv, int streak, int nextMs,
                                      @NotNull Map<Integer, List<AbstractReward>> milestones) {
        int[] grid = {
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43
        };

        int idx = 0;
        for (var entry : milestones.entrySet()) {
            if (idx >= grid.length) break;
            inv.setItem(grid[idx], buildMilestoneItem(entry.getKey(), entry.getValue(), streak, nextMs));
            idx++;
        }

        for (int i = idx; i < grid.length; i++) {
            inv.setItem(grid[i], ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                    .name(Component.empty()).build());
        }
    }

    private @NotNull ItemStack buildMilestoneItem(int day, @NotNull List<AbstractReward> rewards,
                                                    int streak, int nextMs) {
        boolean achieved = streak >= day;
        boolean isNext = !achieved && day == nextMs;

        Material mat;
        String gradient;
        String icon;
        String status;

        if (achieved) {
            mat = Material.LIME_STAINED_GLASS_PANE;
            gradient = "<gradient:#86efac:#16a34a>";
            icon = "✔";
            status = "Unlocked!";
        } else if (isNext) {
            mat = Material.YELLOW_STAINED_GLASS_PANE;
            gradient = "<gradient:#fde047:#f59e0b>";
            icon = "▶";
            status = "Next milestone!";
        } else {
            mat = Material.RED_STAINED_GLASS_PANE;
            gradient = "<gradient:#fca5a5:#dc2626>";
            icon = "✘";
            status = "Locked";
        }

        List<Component> itemLore = new ArrayList<>();
        itemLore.add(Component.empty());
        itemLore.add(lore("  " + gradient + icon + " " + status + "</gradient>"));

        if (!achieved) {
            int remaining = day - streak;
            itemLore.add(lore("  <dark_gray>" + remaining + " day" + plural(remaining) + " to go"));
        }

        itemLore.add(Component.empty());
        itemLore.add(lore("  <gradient:#d8b4fe:#9333ea>Rewards:"));

        for (AbstractReward reward : rewards) {
            itemLore.add(lore("  <dark_gray>▸</dark_gray> <gray>" + formatReward(reward)));
        }
        itemLore.add(Component.empty());

        return ItemBuilder.of(mat)
                .name(name(gradient + "<bold>Day " + day + "</bold></gradient>"))
                .glow(isNext)
                .lore(itemLore)
                .amount(Math.min(day, 64))
                .build();
    }

    // ── Helpers ─────────────────────────────────────────────────

    private static int findNextMilestone(int current, Map<Integer, ?> milestones) {
        for (int day : milestones.keySet()) {
            if (day > current) return day;
        }
        if (!milestones.isEmpty()) {
            return milestones.keySet().stream().mapToInt(Integer::intValue).max().orElse(current);
        }
        return 7;
    }

    private static int countMatching(int streak, Map<Integer, ?> milestones, boolean unlocked) {
        return (int) milestones.keySet().stream()
                .filter(d -> unlocked ? streak >= d : streak < d)
                .count();
    }

    private static String formatReward(@NotNull AbstractReward reward) {
        String typeId = reward.typeId();
        if (typeId == null || typeId.isEmpty()) return "Reward";
        var sb = new StringBuilder();
        boolean cap = true;
        for (char c : typeId.toCharArray()) {
            if (c == '_' || c == '-') { sb.append(' '); cap = true; }
            else { sb.append(cap ? Character.toUpperCase(c) : c); cap = false; }
        }
        return sb.toString();
    }

    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
