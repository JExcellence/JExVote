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

public class VoteStreakView extends BaseView {

    private static final MiniMessage MM = MiniMessage.miniMessage();

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
    protected String[] layout() {
        return new String[]{
                "         ",
                " A B C D ",
                "         ",
                " MMMMMMM ",
                " MMMMMMM ",
                "         "
        };
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

        // ── Row 0: Header bar ───────────────────────────────────────
        render.slot(0, 0, ItemBuilder.of(Material.ORANGE_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(0, 1, ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(0, 2, ItemBuilder.of(Material.ORANGE_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(0, 4, ItemBuilder.of(Material.MAGMA_CREAM)
                .name(MM.deserialize("<gradient:#fde047:#f59e0b><bold>🔥 Streak Rewards</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false))
                .glow(true)
                .lore(List.of(
                        Component.empty(),
                        MM.deserialize("  <gray>Vote daily to build your streak!</gray>"),
                        MM.deserialize("  <gray>Reach milestones for bonus rewards.</gray>"),
                        Component.empty()))
                .build());
        render.slot(0, 6, ItemBuilder.of(Material.ORANGE_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(0, 7, ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(0, 8, ItemBuilder.of(Material.ORANGE_STAINED_GLASS_PANE)
                .name(Component.empty()).build());

        // ── Row 1: Info items ───────────────────────────────────────
        render.slot(1, 0, ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(1, 8, ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.empty()).build());

        // Loading placeholders
        render.slot(1, 1, ItemBuilder.of(Material.BLAZE_POWDER)
                .name(MM.deserialize("<gradient:#fde047:#f59e0b><bold>🔥 Your Streak</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false))
                .lore(List.of(Component.empty(), MM.deserialize("  <gray>Loading...</gray>"), Component.empty()))
                .build());

        render.slot(1, 7, ItemBuilder.of(Material.CLOCK)
                .name(MM.deserialize("<gradient:#a5f3fc:#06b6d4><bold>Progress</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false))
                .lore(List.of(Component.empty(), MM.deserialize("  <gray>Loading...</gray>"), Component.empty()))
                .build());

        // ── Row 2: Separator with accents ───────────────────────────
        render.slot(2, 0, ItemBuilder.of(Material.ORANGE_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(2, 4, ItemBuilder.of(Material.ORANGE_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(2, 8, ItemBuilder.of(Material.ORANGE_STAINED_GLASS_PANE)
                .name(Component.empty()).build());

        // ── Row 3-4: Milestone grid edges ───────────────────────────
        render.slot(3, 0, ItemBuilder.of(Material.ORANGE_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(3, 8, ItemBuilder.of(Material.ORANGE_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(4, 0, ItemBuilder.of(Material.ORANGE_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(4, 8, ItemBuilder.of(Material.ORANGE_STAINED_GLASS_PANE)
                .name(Component.empty()).build());

        // ── Row 5: Bottom bar ───────────────────────────────────────
        render.slot(5, 1, ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(5, 7, ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(5, 8, ItemBuilder.of(Material.ORANGE_STAINED_GLASS_PANE)
                .name(Component.empty()).build());

        // ── Load data and render ────────────────────────────────────
        voteService.getPlayerStats(player.getUniqueId()).thenAccept(stats ->
                scheduler.runAtEntity(player, () -> {
                    int currentStreak = stats.currentStreak();
                    int highestStreak = stats.highestStreak();

                    Map<Integer, List<AbstractReward>> sortedStreaks = new TreeMap<>(rewardService.getStreakRewards());
                    int nextMilestone = findNextMilestone(currentStreak, sortedStreaks);
                    String progressBar = buildProgressBar(currentStreak, nextMilestone, 20);

                    // ── Streak info (row 1, left) ───────────────────
                    render.slot(1, 1, ItemBuilder.of(Material.BLAZE_POWDER)
                            .name(MM.deserialize("<gradient:#fde047:#f59e0b><bold>🔥 Your Streak</bold></gradient>")
                                    .decoration(TextDecoration.ITALIC, false))
                            .glow(currentStreak >= 7)
                            .lore(List.of(
                                    Component.empty(),
                                    MM.deserialize("  <dark_gray>▸</dark_gray> <gray>Current:</gray> <gradient:#86efac:#16a34a><bold>" + currentStreak + "</bold></gradient> <gray>day" + (currentStreak != 1 ? "s" : "") + "</gray>"),
                                    MM.deserialize("  <dark_gray>▸</dark_gray> <gray>Highest:</gray> <gradient:#fde047:#f59e0b><bold>" + highestStreak + "</bold></gradient> <gray>day" + (highestStreak != 1 ? "s" : "") + "</gray>"),
                                    Component.empty(),
                                    MM.deserialize("  <gray>Vote daily to keep it going!</gray>"),
                                    Component.empty()))
                            .build());

                    // ── Progress display (row 1, right) ─────────────
                    render.slot(1, 7, ItemBuilder.of(Material.EXPERIENCE_BOTTLE)
                            .name(MM.deserialize("<gradient:#a5f3fc:#06b6d4><bold>Progress</bold></gradient>")
                                    .decoration(TextDecoration.ITALIC, false))
                            .lore(List.of(
                                    Component.empty(),
                                    MM.deserialize("  <gray>Next milestone:</gray> <white>Day " + nextMilestone + "</white>"),
                                    Component.empty(),
                                    MM.deserialize("  " + progressBar),
                                    MM.deserialize("  <gradient:#86efac:#16a34a>" + currentStreak + "</gradient> <dark_gray>/</dark_gray> <white>" + nextMilestone + "</white>"),
                                    Component.empty()))
                            .build());

                    // ── Info items in header row ────────────────────
                    render.slot(1, 3, ItemBuilder.of(Material.BOOK)
                            .name(MM.deserialize("<gradient:#86efac:#16a34a><bold>How It Works</bold></gradient>")
                                    .decoration(TextDecoration.ITALIC, false))
                            .lore(List.of(
                                    Component.empty(),
                                    MM.deserialize("  <gray>Vote every day to build</gray>"),
                                    MM.deserialize("  <gray>your streak. Reach milestones</gray>"),
                                    MM.deserialize("  <gray>to earn bonus rewards!</gray>"),
                                    Component.empty(),
                                    MM.deserialize("  <gradient:#fca5a5:#dc2626>Missing a day resets</gradient>"),
                                    MM.deserialize("  <gradient:#fca5a5:#dc2626>your streak!</gradient>"),
                                    Component.empty()))
                            .build());

                    render.slot(1, 5, ItemBuilder.of(Material.CHEST)
                            .name(MM.deserialize("<gradient:#fde047:#f59e0b><bold>Rewards</bold></gradient>")
                                    .decoration(TextDecoration.ITALIC, false))
                            .lore(List.of(
                                    Component.empty(),
                                    MM.deserialize("  <gray>Milestones:</gray> <white>" + sortedStreaks.size() + "</white>"),
                                    MM.deserialize("  <gray>Unlocked:</gray> <gradient:#86efac:#16a34a>" + countUnlocked(currentStreak, sortedStreaks) + "</gradient>"),
                                    MM.deserialize("  <gray>Remaining:</gray> <gradient:#fca5a5:#dc2626>" + countLocked(currentStreak, sortedStreaks) + "</gradient>"),
                                    Component.empty()))
                            .build());

                    // ── Milestone grid (rows 3-4, cols 1-7 = 14 slots) ──
                    int[] milestoneSlots = {
                            28, 29, 30, 31, 32, 33, 34,  // row 3: cols 1-7
                            37, 38, 39, 40, 41, 42, 43   // row 4: cols 1-7
                    };

                    int slotIndex = 0;
                    for (var entry : sortedStreaks.entrySet()) {
                        if (slotIndex >= milestoneSlots.length) break;

                        int day = entry.getKey();
                        List<AbstractReward> rewards = entry.getValue();
                        boolean achieved = currentStreak >= day;
                        boolean isNext = !achieved && (slotIndex == 0 ||
                                currentStreak >= getPreviousMilestone(day, sortedStreaks));

                        Material material;
                        String gradient;
                        String statusIcon;
                        String statusText;

                        if (achieved) {
                            material = Material.LIME_STAINED_GLASS_PANE;
                            gradient = "<gradient:#86efac:#16a34a>";
                            statusIcon = "✔";
                            statusText = "Unlocked!";
                        } else if (isNext) {
                            material = Material.YELLOW_STAINED_GLASS_PANE;
                            gradient = "<gradient:#fde047:#f59e0b>";
                            statusIcon = "▶";
                            statusText = "Next milestone!";
                        } else {
                            material = Material.RED_STAINED_GLASS_PANE;
                            gradient = "<gradient:#fca5a5:#dc2626>";
                            statusIcon = "✘";
                            statusText = "Locked";
                        }

                        List<Component> lore = new ArrayList<>();
                        lore.add(Component.empty());
                        lore.add(MM.deserialize("  " + gradient + statusIcon + " " + statusText + "</gradient>"));

                        if (!achieved) {
                            int remaining = day - currentStreak;
                            lore.add(MM.deserialize("  <dark_gray>" + remaining + " day" + (remaining != 1 ? "s" : "") + " to go</dark_gray>"));
                        }

                        lore.add(Component.empty());
                        lore.add(MM.deserialize("  <gradient:#d8b4fe:#9333ea>Rewards:</gradient>"));

                        for (AbstractReward reward : rewards) {
                            String rewardName = formatRewardName(reward);
                            lore.add(MM.deserialize("  <dark_gray>▸</dark_gray> <gray>" + rewardName + "</gray>"));
                        }
                        lore.add(Component.empty());

                        render.slot(milestoneSlots[slotIndex], ItemBuilder.of(material)
                                .name(MM.deserialize(gradient + "<bold>Day " + day + "</bold></gradient>")
                                        .decoration(TextDecoration.ITALIC, false))
                                .glow(isNext)
                                .lore(lore)
                                .amount(Math.min(day, 64))
                                .build());

                        slotIndex++;
                    }

                    // Fill empty milestone slots with dark panes
                    for (int i = slotIndex; i < milestoneSlots.length; i++) {
                        render.slot(milestoneSlots[i], ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                                .name(Component.empty()).build());
                    }
                }));
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static String buildProgressBar(int current, int target, int bars) {
        int filled = target > 0 ? Math.min(bars, (int) ((double) current / target * bars)) : 0;
        StringBuilder sb = new StringBuilder("<dark_gray>[</dark_gray>");
        for (int i = 0; i < bars; i++) {
            if (i < filled) {
                sb.append("<gradient:#fde047:#f59e0b>|</gradient>");
            } else {
                sb.append("<dark_gray>|</dark_gray>");
            }
        }
        sb.append("<dark_gray>]</dark_gray>");
        return sb.toString();
    }

    private static int findNextMilestone(int current, Map<Integer, ?> milestones) {
        for (int day : milestones.keySet()) {
            if (day > current) return day;
        }
        // All milestones achieved — return last one
        if (!milestones.isEmpty()) {
            return milestones.keySet().stream().mapToInt(Integer::intValue).max().orElse(current);
        }
        return 7; // fallback
    }

    private static int getPreviousMilestone(int currentDay, Map<Integer, ?> milestones) {
        int prev = 0;
        for (int day : milestones.keySet()) {
            if (day >= currentDay) break;
            prev = day;
        }
        return prev;
    }

    private static int countUnlocked(int streak, Map<Integer, ?> milestones) {
        return (int) milestones.keySet().stream().filter(d -> streak >= d).count();
    }

    private static int countLocked(int streak, Map<Integer, ?> milestones) {
        return (int) milestones.keySet().stream().filter(d -> streak < d).count();
    }

    private static String formatRewardName(@NotNull AbstractReward reward) {
        String typeId = reward.typeId();
        // Convert type_id to a readable name: "command_reward" -> "Command Reward"
        if (typeId == null || typeId.isEmpty()) return "Reward";

        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : typeId.toCharArray()) {
            if (c == '_' || c == '-') {
                result.append(' ');
                capitalizeNext = true;
            } else {
                result.append(capitalizeNext ? Character.toUpperCase(c) : c);
                capitalizeNext = false;
            }
        }
        return result.toString();
    }
}
