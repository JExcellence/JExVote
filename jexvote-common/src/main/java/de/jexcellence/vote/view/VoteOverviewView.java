package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.utility.item.HeadBuilder;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.jexplatform.view.BaseView;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.vote.service.VoteBroadcastService;
import de.jexcellence.vote.service.VoteService;
import me.devnatan.inventoryframework.context.RenderContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class VoteOverviewView extends BaseView {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final VoteService voteService;
    private final VoteBroadcastService broadcastService;
    private final PlatformScheduler scheduler;

    public VoteOverviewView(@NotNull JavaPlugin plugin,
                            @NotNull VoteService voteService,
                            @NotNull VoteBroadcastService broadcastService) {
        super();
        this.voteService = voteService;
        this.broadcastService = broadcastService;
        this.scheduler = PlatformScheduler.of(plugin);
    }

    @Override
    protected String translationKey() {
        return "vote_overview";
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
        render.slot(0, 0, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(0, 1, ItemBuilder.of(Material.GREEN_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(0, 2, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(0, 4, ItemBuilder.of(Material.EMERALD)
                .name(MM.deserialize("<gradient:#86efac:#16a34a><bold>✦ Vote Overview</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false))
                .glow(true)
                .lore(List.of(
                        Component.empty(),
                        MM.deserialize("  <gray>Vote for us to earn rewards!</gray>"),
                        MM.deserialize("  <gray>Click a site below to get started.</gray>"),
                        Component.empty()))
                .build());
        render.slot(0, 6, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(0, 7, ItemBuilder.of(Material.GREEN_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(0, 8, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.empty()).build());

        // ── Row 1: Player stats ─────────────────────────────────────
        render.slot(1, 0, ItemBuilder.of(Material.GREEN_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(1, 8, ItemBuilder.of(Material.GREEN_STAINED_GLASS_PANE)
                .name(Component.empty()).build());

        // Player head placeholder
        render.slot(1, 3, HeadBuilder.fromPlayer(player)
                .name(MM.deserialize("<gradient:#86efac:#16a34a><bold>" + player.getName() + "</bold></gradient>"))
                .lore(List.of(
                        Component.empty(),
                        MM.deserialize("  <gray>Loading your stats...</gray>"),
                        Component.empty()))
                .build());

        // Placeholder stat items
        render.slot(1, 4, ItemBuilder.of(Material.NETHER_STAR)
                .name(MM.deserialize("<gradient:#d8b4fe:#9333ea><bold>Vote Points</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false))
                .lore(List.of(Component.empty(), MM.deserialize("  <gray>Loading...</gray>"), Component.empty()))
                .build());

        render.slot(1, 5, ItemBuilder.of(Material.BLAZE_POWDER)
                .name(MM.deserialize("<gradient:#fde047:#f59e0b><bold>Vote Streak</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false))
                .lore(List.of(Component.empty(), MM.deserialize("  <gray>Loading...</gray>"), Component.empty()))
                .build());

        // Load stats async and update
        voteService.getPlayerStats(player.getUniqueId()).thenAccept(stats ->
                scheduler.runAtEntity(player, () -> {
                    int streak = stats.currentStreak();
                    int nextMilestone = nextMilestone(streak);
                    String progressBar = buildProgressBar(streak, nextMilestone, 10);

                    render.slot(1, 3, HeadBuilder.fromPlayer(player)
                            .name(MM.deserialize("<gradient:#86efac:#16a34a><bold>" + player.getName() + "</bold></gradient>"))
                            .lore(List.of(
                                    Component.empty(),
                                    MM.deserialize("  <dark_gray>▸</dark_gray> <gray>Total Votes:</gray> <white>" + stats.totalVotes() + "</white>"),
                                    MM.deserialize("  <dark_gray>▸</dark_gray> <gray>Monthly:</gray> <white>" + stats.monthlyVotes() + "</white>"),
                                    MM.deserialize("  <dark_gray>▸</dark_gray> <gray>Vote Points:</gray> <gradient:#d8b4fe:#9333ea>" + stats.votePoints() + "</gradient>"),
                                    Component.empty(),
                                    MM.deserialize("  <gradient:#fde047:#f59e0b>Streak:</gradient> <white>" + streak + "</white> <dark_gray>/</dark_gray> <gray>" + nextMilestone + "</gray>"),
                                    MM.deserialize("  " + progressBar),
                                    MM.deserialize("  <gray>Highest:</gray> <gradient:#fde047:#f59e0b>" + stats.highestStreak() + "</gradient>"),
                                    Component.empty()))
                            .build());

                    render.slot(1, 4, ItemBuilder.of(Material.NETHER_STAR)
                            .name(MM.deserialize("<gradient:#d8b4fe:#9333ea><bold>Vote Points</bold></gradient>")
                                    .decoration(TextDecoration.ITALIC, false))
                            .glow(true)
                            .lore(List.of(
                                    Component.empty(),
                                    MM.deserialize("  <gray>Balance:</gray> <gradient:#d8b4fe:#9333ea>" + stats.votePoints() + "</gradient>"),
                                    Component.empty(),
                                    MM.deserialize("  <dark_gray>Earn points by voting daily!</dark_gray>"),
                                    Component.empty()))
                            .build());

                    render.slot(1, 5, ItemBuilder.of(Material.BLAZE_POWDER)
                            .name(MM.deserialize("<gradient:#fde047:#f59e0b><bold>Vote Streak</bold></gradient>")
                                    .decoration(TextDecoration.ITALIC, false))
                            .glow(streak >= 7)
                            .lore(List.of(
                                    Component.empty(),
                                    MM.deserialize("  <gray>Current:</gray> <gradient:#86efac:#16a34a>" + streak + " day" + (streak != 1 ? "s" : "") + "</gradient>"),
                                    MM.deserialize("  <gray>Highest:</gray> <gradient:#fde047:#f59e0b>" + stats.highestStreak() + " day" + (stats.highestStreak() != 1 ? "s" : "") + "</gradient>"),
                                    Component.empty(),
                                    MM.deserialize("  " + progressBar),
                                    MM.deserialize("  <dark_gray>Next milestone: Day " + nextMilestone + "</dark_gray>"),
                                    Component.empty()))
                            .build());
                }));

        // ── Row 2: Separator with accents ───────────────────────────
        render.slot(2, 0, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(2, 4, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(2, 8, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.empty()).build());

        // ── Row 3: Vote sites ───────────────────────────────────────
        render.slot(3, 0, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(3, 8, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.empty()).build());

        List<VoteSite> sites = new ArrayList<>(voteService.getVoteSites().values());
        int[] siteCols = {1, 2, 3, 4, 5, 6, 7};
        Material[] siteMaterials = {
                Material.EMERALD, Material.DIAMOND, Material.GOLD_INGOT,
                Material.AMETHYST_SHARD, Material.LAPIS_LAZULI, Material.REDSTONE, Material.COPPER_INGOT
        };

        for (int i = 0; i < sites.size() && i < siteCols.length; i++) {
            VoteSite site = sites.get(i);
            Material mat = siteMaterials[i % siteMaterials.length];

            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MM.deserialize("  <dark_gray>▸</dark_gray> <gray>Service:</gray> <white>" + site.serviceName() + "</white>"));
            lore.add(MM.deserialize("  <dark_gray>▸</dark_gray> <gray>Points:</gray> <gradient:#86efac:#16a34a>+" + site.pointsPerVote() + "</gradient>"));
            if (site.voteUrl() != null) {
                lore.add(Component.empty());
                lore.add(MM.deserialize("  <gradient:#fde047:#f59e0b>▶ Click to get vote link</gradient>"));
            }

            render.slot(3, siteCols[i], ItemBuilder.of(mat)
                    .name(MM.deserialize("<gradient:#a5f3fc:#06b6d4><bold>" + site.displayName() + "</bold></gradient>")
                            .decoration(TextDecoration.ITALIC, false))
                    .lore(lore)
                    .build())
                    .onClick(click -> {
                        Player clicker = click.getPlayer();
                        if (site.voteUrl() != null) {
                            clicker.closeInventory();
                            Component message = MM.deserialize(
                                            "<gradient:#86efac:#16a34a>✔</gradient> <gray>Vote for us on</gray> "
                                                    + "<gradient:#a5f3fc:#06b6d4>" + site.displayName() + "</gradient>"
                                                    + "<gray>:</gray> ")
                                    .append(Component.text(site.voteUrl(), NamedTextColor.AQUA)
                                            .clickEvent(ClickEvent.openUrl(site.voteUrl()))
                                            .hoverEvent(HoverEvent.showText(
                                                    Component.text("Click to open in browser", NamedTextColor.YELLOW))));
                            clicker.sendMessage(message);
                        }
                    });
        }

        // Fill remaining site slots with gray glass
        for (int i = sites.size(); i < siteCols.length; i++) {
            render.slot(3, siteCols[i], ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                    .name(Component.empty()).build());
        }

        // ── Row 4: Separator with accents ───────────────────────────
        render.slot(4, 0, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(4, 4, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(4, 8, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.empty()).build());

        // ── Row 5: Navigation buttons ───────────────────────────────
        render.slot(5, 0, ItemBuilder.of(Material.GREEN_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(5, 8, ItemBuilder.of(Material.GREEN_STAINED_GLASS_PANE)
                .name(Component.empty()).build());

        render.slot(5, 2, ItemBuilder.of(Material.GOLD_BLOCK)
                .name(MM.deserialize("<gradient:#fde047:#f59e0b><bold>⭐ Leaderboard</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false))
                .glow(true)
                .lore(List.of(
                        Component.empty(),
                        MM.deserialize("  <gray>See who voted the most!</gray>"),
                        Component.empty(),
                        MM.deserialize("  <gradient:#fde047:#f59e0b>▶ Click to view</gradient>"),
                        Component.empty()))
                .build())
                .onClick(click -> click.openForPlayer(VoteLeaderboardView.class));

        render.slot(5, 6, ItemBuilder.of(Material.MAGMA_CREAM)
                .name(MM.deserialize("<gradient:#fca5a5:#dc2626><bold>🔥 Streak Rewards</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false))
                .glow(true)
                .lore(List.of(
                        Component.empty(),
                        MM.deserialize("  <gray>View milestone rewards!</gray>"),
                        Component.empty(),
                        MM.deserialize("  <gradient:#fca5a5:#dc2626>▶ Click to view</gradient>"),
                        Component.empty()))
                .build())
                .onClick(click -> click.openForPlayer(VoteStreakView.class));
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static String buildProgressBar(int current, int target, int bars) {
        int filled = target > 0 ? Math.min(bars, (int) ((double) current / target * bars)) : 0;
        StringBuilder sb = new StringBuilder("<dark_gray>[</dark_gray>");
        for (int i = 0; i < bars; i++) {
            if (i < filled) {
                sb.append("<gradient:#86efac:#16a34a>|</gradient>");
            } else {
                sb.append("<dark_gray>|</dark_gray>");
            }
        }
        sb.append("<dark_gray>]</dark_gray>");
        return sb.toString();
    }

    private int nextMilestone(int currentStreak) {
        int[] milestones = {7, 14, 30, 60, 90, 120, 180, 365};
        for (int m : milestones) {
            if (currentStreak < m) return m;
        }
        return milestones[milestones.length - 1];
    }
}
