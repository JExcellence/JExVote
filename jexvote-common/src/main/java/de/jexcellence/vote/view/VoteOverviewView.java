package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.utility.item.HeadBuilder;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.vote.service.VoteService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Main vote overview GUI. Shows player stats, vote sites, and navigation
 * to leaderboard / streak views.
 */
public class VoteOverviewView extends VoteBaseView {

    /*
     * Slot grid (6 × 9):
     *   0  1  2  3  4  5  6  7  8
     *   9 10 11 12 13 14 15 16 17
     *  18 19 20 21 22 23 24 25 26
     *  27 28 29 30 31 32 33 34 35
     *  36 37 38 39 40 41 42 43 44
     *  45 46 47 48 49 50 51 52 53
     */

    private static final String GRADIENT_END = "</bold></gradient>";

    private final Holder holder = new Holder();
    private final VoteService voteService;
    private final PlatformScheduler scheduler;

    private VoteLeaderboardView leaderboardView;
    private VoteStreakView streakView;

    public VoteOverviewView(@NotNull JavaPlugin plugin,
                            @NotNull VoteService voteService) {
        this.voteService = voteService;
        this.scheduler = PlatformScheduler.of(plugin);
    }

    /**
     * Sets the leaderboard view for navigation.
     *
     * @param view the leaderboard view
     */
    public void setLeaderboardView(@NotNull VoteLeaderboardView view) { this.leaderboardView = view; }

    /**
     * Sets the streak view for navigation.
     *
     * @param view the streak view
     */
    public void setStreakView(@NotNull VoteStreakView view) { this.streakView = view; }

    @Override protected @NotNull String title()           { return "vote_overview.title"; }
    @Override protected int rows()                         { return 6; }
    @Override protected @NotNull InventoryHolder holder()  { return holder; }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {

        // ── Row 0: Header ───────────────────────────────────────
        glass(inv, Material.LIME_STAINED_GLASS_PANE, 0, 2, 6, 8);
        glass(inv, Material.GREEN_STAINED_GLASS_PANE, 1, 7);
        inv.setItem(4, ItemBuilder.of(Material.EMERALD)
                .name(name("<gradient:#86efac:#16a34a><bold>✦ Vote Overview" + GRADIENT_END))
                .glow(true)
                .lore(List.of(
                        Component.empty(),
                        lore("  <gray>Vote for us to earn rewards!"),
                        lore("  <gray>Click a site below to get started."),
                        Component.empty()))
                .build());

        // ── Row 1: Player info (placeholders) ───────────────────
        glass(inv, Material.GREEN_STAINED_GLASS_PANE, 9, 17);

        inv.setItem(12, HeadBuilder.fromPlayer(viewer)
                .name(name("<gradient:#86efac:#16a34a><bold>" + viewer.getName() + GRADIENT_END))
                .lore(List.of(
                        Component.empty(),
                        lore("  <gray>Loading stats..."),
                        Component.empty()))
                .build());

        inv.setItem(13, ItemBuilder.of(Material.NETHER_STAR)
                .name(name("<gradient:#d8b4fe:#9333ea><bold>Vote Points" + GRADIENT_END))
                .lore(List.of(Component.empty(), lore("  <gray>Loading..."), Component.empty()))
                .build());

        inv.setItem(14, ItemBuilder.of(Material.BLAZE_POWDER)
                .name(name("<gradient:#fde047:#f59e0b><bold>Vote Streak" + GRADIENT_END))
                .lore(List.of(Component.empty(), lore("  <gray>Loading..."), Component.empty()))
                .build());

        // ── Async: update stats via raw inventory ───────────────
        voteService.getPlayerStats(viewer.getUniqueId()).thenAccept(stats ->
                scheduler.runAtEntity(viewer, () -> {
                    Inventory top = viewer.getOpenInventory().getTopInventory();
                    if (top.getHolder() != holder) return; // GUI closed

                    int streak = stats.currentStreak();
                    int nextMs = nextMilestone(streak);
                    String bar = progressBar(streak, nextMs, 10);

                    top.setItem(12, HeadBuilder.fromPlayer(viewer)
                            .name(name("<gradient:#86efac:#16a34a><bold>" + viewer.getName() + GRADIENT_END))
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

                    top.setItem(13, ItemBuilder.of(Material.NETHER_STAR)
                            .name(name("<gradient:#d8b4fe:#9333ea><bold>Vote Points" + GRADIENT_END))
                            .glow(true)
                            .lore(List.of(
                                    Component.empty(),
                                    lore("  <gray>Balance:</gray> <gradient:#d8b4fe:#9333ea>" + stats.votePoints()),
                                    Component.empty(),
                                    lore("  <dark_gray>Earn points by voting daily!"),
                                    Component.empty()))
                            .build());

                    top.setItem(14, ItemBuilder.of(Material.BLAZE_POWDER)
                            .name(name("<gradient:#fde047:#f59e0b><bold>Vote Streak" + GRADIENT_END))
                            .glow(streak >= 7)
                            .lore(List.of(
                                    Component.empty(),
                                    lore("  <gray>Current:</gray> <gradient:#86efac:#16a34a>" + streak + " day" + plural(streak)),
                                    lore("  <gray>Highest:</gray> <gradient:#fde047:#f59e0b>" + stats.highestStreak() + " day" + plural(stats.highestStreak())),
                                    Component.empty(),
                                    lore("  " + bar),
                                    lore("  <dark_gray>Next milestone: Day " + nextMs),
                                    Component.empty()))
                            .build());
                }));

        // ── Row 2: Separator ────────────────────────────────────
        glass(inv, Material.LIME_STAINED_GLASS_PANE, 18, 22, 26);

        // ── Row 3: Vote sites ───────────────────────────────────
        glass(inv, Material.LIME_STAINED_GLASS_PANE, 27, 35);

        List<VoteSite> sites = new ArrayList<>(voteService.getVoteSites().values());
        Material[] siteMats = {
                Material.EMERALD, Material.DIAMOND, Material.GOLD_INGOT,
                Material.AMETHYST_SHARD, Material.LAPIS_LAZULI, Material.REDSTONE, Material.COPPER_INGOT
        };

        for (int i = 0; i < 7; i++) {
            int slot = 28 + i;
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
                ItemStack siteItem = ItemBuilder.of(siteMats[i % siteMats.length])
                        .name(name("<gradient:#a5f3fc:#06b6d4><bold>" + site.displayName() + GRADIENT_END))
                        .lore(siteLore)
                        .build();
                tag(siteItem, "site:" + site.serviceName());
                inv.setItem(slot, siteItem);
            } else {
                inv.setItem(slot, ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                        .name(Component.empty()).build());
            }
        }

        // ── Row 4: Separator ────────────────────────────────────
        glass(inv, Material.LIME_STAINED_GLASS_PANE, 36, 40, 44);

        // ── Row 5: Navigation ───────────────────────────────────
        glass(inv, Material.GREEN_STAINED_GLASS_PANE, 46, 52);

        ItemStack lbItem = ItemBuilder.of(Material.GOLD_BLOCK)
                .name(name("<gradient:#fde047:#f59e0b><bold>⭐ Leaderboard" + GRADIENT_END))
                .glow(true)
                .lore(List.of(
                        Component.empty(),
                        lore("  <gray>See who voted the most!"),
                        Component.empty(),
                        lore("  <gradient:#fde047:#f59e0b>▶ Click to view"),
                        Component.empty()))
                .build();
        tag(lbItem, "leaderboard");
        inv.setItem(47, lbItem);

        ItemStack streakItem = ItemBuilder.of(Material.MAGMA_CREAM)
                .name(name("<gradient:#fca5a5:#dc2626><bold>🔥 Streak Rewards" + GRADIENT_END))
                .glow(true)
                .lore(List.of(
                        Component.empty(),
                        lore("  <gray>View milestone rewards!"),
                        Component.empty(),
                        lore("  <gradient:#fca5a5:#dc2626>▶ Click to view"),
                        Component.empty()))
                .build();
        tag(streakItem, "streaks");
        inv.setItem(51, streakItem);
    }

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        String id = tagOf(clicked);
        if (id == null) return;

        if ("leaderboard".equals(id) && leaderboardView != null) {
            leaderboardView.open(viewer);
            return;
        }
        if ("streaks".equals(id) && streakView != null) {
            streakView.open(viewer);
            return;
        }
        if (id.startsWith("site:")) {
            String serviceName = id.substring(5);
            VoteSite site = voteService.getVoteSites().values().stream()
                    .filter(s -> serviceName.equals(s.serviceName()))
                    .findFirst().orElse(null);
            if (site != null && site.voteUrl() != null) {
                viewer.closeInventory();
                viewer.sendMessage(
                        MM.deserialize("<gradient:#86efac:#16a34a>✔</gradient> <gray>Vote on</gray> <gradient:#a5f3fc:#06b6d4>" + site.displayName() + "</gradient><gray>:</gray> ")
                                .append(Component.text(site.voteUrl(), NamedTextColor.AQUA)
                                        .clickEvent(ClickEvent.openUrl(site.voteUrl()))
                                        .hoverEvent(HoverEvent.showText(
                                                Component.text("Click to open in browser", NamedTextColor.YELLOW)))));
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    /**
     * Returns the next milestone day based on the current streak.
     *
     * @param streak the current vote streak
     * @return the next milestone day
     */
    private static int nextMilestone(int streak) {
        for (int m : new int[]{7, 14, 30, 60, 90, 120, 180, 365}) {
            if (streak < m) return m;
        }
        return 365;
    }

    /**
     * Inventory holder for the overview view.
     */
    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
