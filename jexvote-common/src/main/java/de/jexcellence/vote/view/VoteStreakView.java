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
                "         ",
                "         ",
                "         ",
                "         "
        };
    }

    @Override
    protected int size() {
        return 5;
    }

    @Override
    protected void onRender(@NotNull RenderContext render, @NotNull Player player) {
        // Place a loading indicator first
        render.slot(0, 4, ItemBuilder.of(Material.BLAZE_POWDER)
                .name(MM.deserialize("<gradient:#fca5a5:#dc2626><bold>Your Streak</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false))
                .lore(List.of(MM.deserialize("<gray>Loading...</gray>")))
                .build());

        voteService.getPlayerStats(player.getUniqueId()).thenAccept(stats ->
                scheduler.runAtEntity(player, () -> {
                    int currentStreak = stats.currentStreak();

                    // ── Header item (center top) ────────────────────
                    render.slot(0, 4, ItemBuilder.of(Material.BLAZE_POWDER)
                            .name(MM.deserialize("<gradient:#fca5a5:#dc2626><bold>Your Streak</bold></gradient>")
                                    .decoration(TextDecoration.ITALIC, false))
                            .lore(List.of(
                                    Component.empty(),
                                    MM.deserialize("  <gray>Current:</gray> <gradient:#86efac:#16a34a>" + currentStreak + " days</gradient>"),
                                    MM.deserialize("  <gray>Highest:</gray> <gradient:#fde047:#f59e0b>" + stats.highestStreak() + " days</gradient>"),
                                    Component.empty(),
                                    MM.deserialize("  <gray>Vote daily to keep your streak!</gray>")
                            ))
                            .build());

                    // ── Streak milestone items (rows 1-3) ───────────
                    Map<Integer, List<AbstractReward>> sortedStreaks = new TreeMap<>(rewardService.getStreakRewards());

                    // Place milestones centered in rows 1-3 (up to 15 milestones)
                    int[] milestoneSlots = {
                            10, 11, 12, 13, 14, 15, 16,  // row 1: cols 1-7
                            19, 20, 21, 22, 23, 24, 25,  // row 2: cols 1-7
                            28, 29, 30, 31, 32, 33, 34   // row 3: cols 1-7
                    };

                    int slotIndex = 0;
                    for (var entry : sortedStreaks.entrySet()) {
                        if (slotIndex >= milestoneSlots.length) break;

                        int day = entry.getKey();
                        List<AbstractReward> rewards = entry.getValue();
                        boolean achieved = currentStreak >= day;

                        Material material = achieved ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
                        String gradient = achieved
                                ? "<gradient:#86efac:#16a34a>"
                                : "<gradient:#fca5a5:#dc2626>";
                        String statusIcon = achieved ? "✔" : "✘";

                        List<Component> lore = new ArrayList<>();
                        lore.add(Component.empty());
                        lore.add(MM.deserialize("  " + gradient + statusIcon + " "
                                + (achieved ? "Unlocked!" : "Locked") + "</gradient>"));
                        lore.add(Component.empty());
                        lore.add(MM.deserialize("  <gradient:#fde047:#f59e0b>Rewards:</gradient>"));

                        for (AbstractReward reward : rewards) {
                            lore.add(MM.deserialize("  <dark_gray>▸</dark_gray> <gray>" + reward.typeId() + "</gray>"));
                        }

                        render.slot(milestoneSlots[slotIndex], ItemBuilder.of(material)
                                .name(MM.deserialize(gradient + "<bold>Day " + day + "</bold></gradient>")
                                        .decoration(TextDecoration.ITALIC, false))
                                .lore(lore)
                                .build());

                        slotIndex++;
                    }
                }));
    }
}
