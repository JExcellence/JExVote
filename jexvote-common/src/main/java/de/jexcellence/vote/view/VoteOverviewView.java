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
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Main vote overview GUI. Shows player stats, vote sites, and navigation
 * to leaderboard / streak views.
 *
 * <p>Uses absolute slot indices exclusively (no layout override) so that
 * BaseView's auto-fill handles all unset slots with {@link #fillerMaterial()}.
 * Async stat updates use raw Bukkit inventory to bypass the framework's
 * single-render-phase limitation.
 */
public class VoteOverviewView extends BaseView {

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

        // ── Row 0: Header ───────────────────────────────────────────
        glass(render, Material.LIME_STAINED_GLASS_PANE, 0, 2, 6, 8);
        glass(render, Material.GREEN_STAINED_GLASS_PANE, 1, 7);
        render.slot(4, ItemBuilder.of(Material.EMERALD)
                .name(name("<gradient:#86efac:#16a34a><bold>✦ Vote Overview</bold></gradient>"))
                .glow(true)
                .lore(List.of(
                        Component.empty(),
                        lore("  <gray>Vote for us to earn rewards!"),
                        lore("  <gray>Click a site below to get started."),
                        Component.empty()))
                .build());

        // ── Row 1: Player info (initial placeholders) ──────────────
        glass(render, Material.GREEN_STAINED_GLASS_PANE, 9, 17);

        render.slot(12, HeadBuilder.fromPlayer(player)
                .name(name("<gradient:#86efac:#16a34a><bold>" + player.getName() + "</bold></gradient>"))
                .lore(List.of(
                        Component.empty(),
                        lore("  <gray>Loading stats..."),
                        Component.empty()))
                .build());

        render.slot(13, ItemBuilder.of(Material.NETHER_STAR)
                .name(name("<gradient:#d8b4fe:#9333ea><bold>Vote Points</bold></gradient>"))
                .lore(List.of(Component.empty(), lore("  <gray>Loading..."), Component.empty()))
                .build());

        render.slot(14, ItemBuilder.of(Material.BLAZE_POWDER)
                .name(name("<gradient:#fde047:#f59e0b><bold>Vote Streak</bold></gradient>"))
                .lore(List.of(Component.empty(), lore("  <gray>Loading..."), Component.empty()))
                .build());

        // ── Async: update stat slots via raw Bukkit inventory ───────
        voteService.getPlayerStats(player.getUniqueId()).thenAccept(stats ->
                scheduler.runAtEntity(player, () -> {
                    Inventory inv = player.getOpenInventory().getTopInventory();

                    int streak = stats.currentStreak();
                    int nextMs = nextMilestone(streak);
                    String bar = progressBar(streak, nextMs, 10);

                    inv.setItem(12, HeadBuilder.fromPlayer(player)
                            .name(name("<gradient:#86efac:#16a34a><bold>" + player.getName() + "</bold></gradient>"))
                            .lore(List.of(
                                    Component.empty(),
                                    lore("  <dark_gray>▸</dark_gray> <gray>Total Votes:</gray> <white>" + stats.totalVotes()),
                                    lore("  <dark_gray>▸</dark_gray> <gray>Monthly:</gray> <white>" + stats.monthlyVotes()),
                                    lore("  <dark_gray>▸</dark_gray> <gray>Vote Points:</gray> <gradient:#d8b4fe:#9333ea>" + stats.votePoints()),
                                    Component.empty(),
                                    lore("  <gradient:#fde047:#f59e0b>Streak:</gradient> <white>" + streak + "</white> <dark_gray>/</dark_gray> <gray>" + nextMs),
                                    lore("  " + bar),
                                    lore("  <gray>Highest:</gray> <gradient:#fde047:#f59e0b>" + stats.highestStreak()),
                                    Component.empty()))
                            .build());

                    inv.setItem(13, ItemBuilder.of(Material.NETHER_STAR)
                            .name(name("<gradient:#d8b4fe:#9333ea><bold>Vote Points</bold></gradient>"))
                            .glow(true)
                            .lore(List.of(
                                    Component.empty(),
                                    lore("  <gray>Balance:</gray> <gradient:#d8b4fe:#9333ea>" + stats.votePoints()),
                                    Component.empty(),
                                    lore("  <dark_gray>Earn points by voting daily!"),
                                    Component.empty()))
                            .build());

                    inv.setItem(14, ItemBuilder.of(Material.BLAZE_POWDER)
                            .name(name("<gradient:#fde047:#f59e0b><bold>Vote Streak</bold></gradient>"))
                            .glow(streak >= 7)
                            .lore(List.of(
                                    Component.empty(),
                                    lore("  <gray>Current:</gray> <gradient:#86efac:#16a34a>" + streak + " day" + (streak != 1 ? "s" : "")),
                                    lore("  <gray>Highest:</gray> <gradient:#fde047:#f59e0b>" + stats.highestStreak() + " day" + (stats.highestStreak() != 1 ? "s" : "")),
                                    Component.empty(),
                                    lore("  " + bar),
                                    lore("  <dark_gray>Next milestone: Day " + nextMs),
                                    Component.empty()))
                            .build());
                }));

        // ── Row 2: Separator ────────────────────────────────────────
        glass(render, Material.LIME_STAINED_GLASS_PANE, 18, 22, 26);

        // ── Row 3: Vote sites ───────────────────────────────────────
        glass(render, Material.LIME_STAINED_GLASS_PANE, 27, 35);

        List<VoteSite> sites = new ArrayList<>(voteService.getVoteSites().values());
        Material[] siteMats = {
                Material.EMERALD, Material.DIAMOND, Material.GOLD_INGOT,
                Material.AMETHYST_SHARD, Material.LAPIS_LAZULI, Material.REDSTONE, Material.COPPER_INGOT
        };

        for (int i = 0; i < 7; i++) {
            int slot = 28 + i; // slots 28–34
            if (i < sites.size()) {
                VoteSite site = sites.get(i);
                List<Component> siteLore = new ArrayList<>();
                siteLore.add(Component.empty());
                siteLore.add(lore("  <dark_gray>▸</dark_gray> <gray>Service:</gray> <white>" + site.serviceName()));
                siteLore.add(lore("  <dark_gray>▸</dark_gray> <gray>Points:</gray> <gradient:#86efac:#16a34a>+" + site.pointsPerVote()));
                if (site.voteUrl() != null) {
                    siteLore.add(Component.empty());
                    siteLore.add(lore("  <gradient:#fde047:#f59e0b>▶ Click to get vote link"));
                }

                render.slot(slot, ItemBuilder.of(siteMats[i % siteMats.length])
                        .name(name("<gradient:#a5f3fc:#06b6d4><bold>" + site.displayName() + "</bold></gradient>"))
                        .lore(siteLore)
                        .build())
                        .onClick(click -> {
                            if (site.voteUrl() != null) {
                                click.getPlayer().closeInventory();
                                click.getPlayer().sendMessage(
                                        MM.deserialize("<gradient:#86efac:#16a34a>✔</gradient> <gray>Vote on</gray> <gradient:#a5f3fc:#06b6d4>" + site.displayName() + "</gradient><gray>:</gray> ")
                                                .append(Component.text(site.voteUrl(), NamedTextColor.AQUA)
                                                        .clickEvent(ClickEvent.openUrl(site.voteUrl()))
                                                        .hoverEvent(HoverEvent.showText(
                                                                Component.text("Click to open in browser", NamedTextColor.YELLOW)))));
                            }
                        });
            } else {
                render.slot(slot, ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                        .name(Component.empty()).build());
            }
        }

        // ── Row 4: Separator ────────────────────────────────────────
        glass(render, Material.LIME_STAINED_GLASS_PANE, 36, 40, 44);

        // ── Row 5: Navigation ───────────────────────────────────────
        glass(render, Material.GREEN_STAINED_GLASS_PANE, 46, 52);

        render.slot(47, ItemBuilder.of(Material.GOLD_BLOCK)
                .name(name("<gradient:#fde047:#f59e0b><bold>⭐ Leaderboard</bold></gradient>"))
                .glow(true)
                .lore(List.of(
                        Component.empty(),
                        lore("  <gray>See who voted the most!"),
                        Component.empty(),
                        lore("  <gradient:#fde047:#f59e0b>▶ Click to view"),
                        Component.empty()))
                .build())
                .onClick(click -> click.openForPlayer(VoteLeaderboardView.class));

        render.slot(51, ItemBuilder.of(Material.MAGMA_CREAM)
                .name(name("<gradient:#fca5a5:#dc2626><bold>🔥 Streak Rewards</bold></gradient>"))
                .glow(true)
                .lore(List.of(
                        Component.empty(),
                        lore("  <gray>View milestone rewards!"),
                        Component.empty(),
                        lore("  <gradient:#fca5a5:#dc2626>▶ Click to view"),
                        Component.empty()))
                .build())
                .onClick(click -> click.openForPlayer(VoteStreakView.class));
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

    private static String progressBar(int current, int target, int bars) {
        int filled = target > 0 ? Math.min(bars, (int) ((double) current / target * bars)) : 0;
        var sb = new StringBuilder("<dark_gray>[</dark_gray>");
        for (int i = 0; i < bars; i++) {
            sb.append(i < filled ? "<gradient:#86efac:#16a34a>|</gradient>" : "<dark_gray>|</dark_gray>");
        }
        sb.append("<dark_gray>]</dark_gray>");
        return sb.toString();
    }

    private static int nextMilestone(int streak) {
        for (int m : new int[]{7, 14, 30, 60, 90, 120, 180, 365}) {
            if (streak < m) return m;
        }
        return 365;
    }
}
