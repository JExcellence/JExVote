package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.jexplatform.view.BaseView;
import de.jexcellence.vote.service.VoteRewardService;
import de.jexcellence.vote.service.VoteService;
import me.devnatan.inventoryframework.context.RenderContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Streak milestone view showing current streak progress and
 * unlockable rewards at each milestone day.
 *
 * <p>Uses absolute slot indices exclusively (no layout override) so that
 * BaseView's auto-fill handles all unset slots with {@link #fillerMaterial()}.
 */
public class VoteStreakView extends BaseView {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /*
     * Slot grid reference (6 rows × 9 cols):
     *   0  1  2  3  4  5  6  7  8
     *   9 10 11 12 13 14 15 16 17
     *  18 19 20 21 22 23 24 25 26
     *  27 28 29 30 31 32 33 34 35
     *  36 37 38 39 40 41 42 43 44
     *  45 46 47 48 49 50 51 52 53
     */

    private final VoteService voteService;
    private final VoteRewardService rewardService;
    private final PlatformScheduler scheduler;

    public VoteStreakView(@NotNull JavaPlugin plugin,
                          @NotNull VoteService voteService,
                          @NotNull VoteRewardService rewardService) {
        super(VoteOverviewView.class);
        this.voteService = voteService;
        this.rewardService = rewardService;
        this.scheduler = PlatformScheduler.of(plugin);
    }

    @Override
    protected String translationKey() {
        return "vote_streak";
    }

    @Override
    protected int size() {
        return 6;
    }

    @Override
    protected Material fillerMaterial() {
        return Material.BLACK_STAINED_GLASS_PANE;
    }

    @Override
    protected void onRender(@NotNull RenderContext render, @NotNull Player player) {

        // ── Row 0: Header ───────────────────────────────────────────
        glass(render, Material.ORANGE_STAINED_GLASS_PANE, 0, 2, 6, 8);
        glass(render, Material.YELLOW_STAINED_GLASS_PANE, 1, 7);
        render.slot(4, ItemBuilder.of(Material.MAGMA_CREAM)
                .name(name("<gradient:#fca5a5:#dc2626><bold>🔥 Streak Rewards</bold></gradient>"))
                .glow(true)
                .lore(List.of(
                        Component.empty(),
                        lore("  <gray>Vote daily to build your streak!"),
                        lore("  <gray>Reach milestones for bonus rewards."),
                        Component.empty()))
                .build());

        // ── Row 1: Info items ───────────────────────────────────────
        glass(render, Material.YELLOW_STAINED_GLASS_PANE, 9, 17);

        // Placeholder items while async data loads
        render.slot(11, ItemBuilder.of(Material.BLAZE_POWDER)
                .name(name("<gradient:#fde047:#f59e0b><bold>🔥 Your Streak</bold></gradient>"))
                .lore(List.of(Component.empty(), lore("  <gray>Loading..."), Component.empty()))
                .build());

        render.slot(13, ItemBuilder.of(Material.BOOK)
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

        render.slot(15, ItemBuilder.of(Material.EXPERIENCE_BOTTLE)
                .name(name("<gradient:#a5f3fc:#06b6d4><bold>Progress</bold></gradient>"))
                .lore(List.of(Component.empty(), lore("  <gray>Loading..."), Component.empty()))
                .build());

        // ── Row 2: Separator ────────────────────────────────────────
        glass(render, Material.ORANGE_STAINED_GLASS_PANE, 18, 22, 26);

        // ── Row 3–4: Milestone grid edges ───────────────────────────
        glass(render, Material.ORANGE_STAINED_GLASS_PANE, 27, 35, 36, 44);

        // ── Row 5: Bottom bar ───────────────────────────────────────
        glass(render, Material.YELLOW_STAINED_GLASS_PANE, 46, 52);
        glass(render, Material.ORANGE_STAINED_GLASS_PANE, 47, 53);

        // ── Async: load stats & milestone data ──────────────────────
        voteService.getPlayerStats(player.getUniqueId()).thenAccept(stats ->
                scheduler.runAtEntity(player, () -> {
                    int streak = stats.currentStreak();
                    int highest = stats.highestStreak();

                    Map<Integer, List<AbstractReward>> milestones =
                            new TreeMap<>(rewardService.getStreakRewards());
                    int nextMs = findNextMilestone(streak, milestones);
                    String bar = progressBar(streak, nextMs, 20);

                    // ── Update: streak info (slot 11) ───────────────
                    render.slot(11, ItemBuilder.of(Material.BLAZE_POWDER)
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

                    // ── Update: rewards summary (slot 13) ───────────
                    int unlocked = countMatching(streak, milestones, true);
                    int locked = countMatching(streak, milestones, false);

                    render.slot(13, ItemBuilder.of(Material.CHEST)
                            .name(name("<gradient:#fde047:#f59e0b><bold>Rewards</bold></gradient>"))
                            .lore(List.of(
                                    Component.empty(),
                                    lore("  <gray>Milestones:</gray> <white>" + milestones.size()),
                                    lore("  <gray>Unlocked:</gray> <gradient:#86efac:#16a34a>" + unlocked),
                                    lore("  <gray>Remaining:</gray> <gradient:#fca5a5:#dc2626>" + locked),
                                    Component.empty()))
                            .build());

                    // ── Update: progress (slot 15) ──────────────────
                    render.slot(15, ItemBuilder.of(Material.EXPERIENCE_BOTTLE)
                            .name(name("<gradient:#a5f3fc:#06b6d4><bold>Progress</bold></gradient>"))
                            .lore(List.of(
                                    Component.empty(),
                                    lore("  <gray>Next milestone:</gray> <white>Day " + nextMs),
                                    Component.empty(),
                                    lore("  " + bar),
                                    lore("  <gradient:#86efac:#16a34a>" + streak + "</gradient> <dark_gray>/</dark_gray> <white>" + nextMs),
                                    Component.empty()))
                            .build());

                    // ── Milestone grid (rows 3–4, cols 1–7) ─────────
                    int[] grid = {
                            28, 29, 30, 31, 32, 33, 34,   // row 3
                            37, 38, 39, 40, 41, 42, 43    // row 4
                    };

                    int idx = 0;
                    for (var entry : milestones.entrySet()) {
                        if (idx >= grid.length) break;

                        int day = entry.getKey();
                        List<AbstractReward> rewards = entry.getValue();
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

                        render.slot(grid[idx], ItemBuilder.of(mat)
                                .name(name(gradient + "<bold>Day " + day + "</bold></gradient>"))
                                .glow(isNext)
                                .lore(itemLore)
                                .amount(Math.min(day, 64))
                                .build());

                        idx++;
                    }

                    // Fill remaining milestone slots
                    for (int i = idx; i < grid.length; i++) {
                        render.slot(grid[i], ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                                .name(Component.empty()).build());
                    }
                }));
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static Component name(String mini) {
        return MM.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    private static Component lore(String mini) {
        return MM.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    private static void glass(@NotNull RenderContext render, @NotNull Material mat, int... slots) {
        var item = ItemBuilder.of(mat).name(Component.empty()).build();
        for (int s : slots) render.slot(s, item);
    }

    private static String plural(int n) {
        return n != 1 ? "s" : "";
    }

    private static String progressBar(int current, int target, int bars) {
        int filled = target > 0 ? Math.min(bars, (int) ((double) current / target * bars)) : 0;
        var sb = new StringBuilder("<dark_gray>[</dark_gray>");
        for (int i = 0; i < bars; i++) {
            sb.append(i < filled ? "<gradient:#fde047:#f59e0b>|</gradient>" : "<dark_gray>|</dark_gray>");
        }
        sb.append("<dark_gray>]</dark_gray>");
        return sb.toString();
    }

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
            if (c == '_' || c == '-') {
                sb.append(' ');
                cap = true;
            } else {
                sb.append(cap ? Character.toUpperCase(c) : c);
                cap = false;
            }
        }
        return sb.toString();
    }
}
