package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.utility.item.HeadBuilder;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.service.VoteLeaderboardService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Paginated all-time vote leaderboard with manual pagination
 * (raw Bukkit inventory, same pattern as JExOneblock's BiomeView).
 */
public class VoteLeaderboardView extends VoteBaseView {

    private static final int PAGE_SIZE = 21; // 3 rows × 7 cols

    private final Holder holder = new Holder();
    private final VoteLeaderboardService leaderboardService;
    private final PlatformScheduler scheduler;
    private final WeakHashMap<UUID, Integer> pageByViewer = new WeakHashMap<>();
    private final WeakHashMap<UUID, List<VoteSnapshot>> dataByViewer = new WeakHashMap<>();

    private VoteOverviewView overviewView;

    public VoteLeaderboardView(@NotNull JavaPlugin plugin,
                               @NotNull VoteLeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
        this.scheduler = PlatformScheduler.of(plugin);
    }

    /**
     * Sets the overview view for back navigation.
     *
     * @param view the overview view to navigate back to
     */
    public void setOverviewView(@NotNull VoteOverviewView view) { this.overviewView = view; }

    @Override protected @NotNull String title()           { return "vote_leaderboard.title"; }
    @Override protected int rows()                         { return 6; }
    @Override protected @NotNull InventoryHolder holder()  { return holder; }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {

        // ── 1-wide frame; podium + leaderboard sit in the interior ──
        frame(inv, Material.YELLOW_STAINED_GLASS_PANE);

        // Podium tile names use the brand glyph (no <bold>) so they stay in
        // visual harmony with the rest of the V-00-styled GUI. The leaderboard
        // entries below remain bold for emphasis on the top-3 player names.
        inv.setItem(2, ItemBuilder.of(Material.DIAMOND)
                .name(name("<gradient:#FFD700:#FFA500>★ 1st Place</gradient>"))
                .lore(List.of(lore("<dark_gray>┃ <gray>Diamond rank")))
                .build());

        inv.setItem(4, ItemBuilder.of(Material.GOLDEN_APPLE)
                .name(name("<gradient:#FDE047:#F59E0B>❖ Top Voters</gradient>"))
                .glow(true)
                .lore(List.of(
                        Component.empty(),
                        lore("<dark_gray>┃ <gray>All-time vote leaderboard"),
                        lore("<dark_gray>┃ <gray>Vote daily to climb!"),
                        Component.empty()))
                .build());

        inv.setItem(6, ItemBuilder.of(Material.GOLD_INGOT)
                .name(name("<gradient:#C0C0C0:#A8A8A8>★ 2nd Place</gradient>"))
                .lore(List.of(lore("<dark_gray>┃ <gray>Gold rank")))
                .build());

        // ── Row 5: Navigation (back top-left, close bottom-left) ──
        navBar(inv, overviewView != null);

        // ── Loading indicator ───────────────────────────────────
        inv.setItem(22, ItemBuilder.of(Material.CLOCK)
                .name(name("<gray>Loading leaderboard..."))
                .build());

        // ── Async: load data and populate ───────────────────────
        leaderboardService.getAllTimeTop(50).thenAccept(data ->
                scheduler.runAtEntity(viewer, () -> {
                    Inventory top = viewer.getOpenInventory().getTopInventory();
                    if (top.getHolder() != holder) return;

                    dataByViewer.put(viewer.getUniqueId(), data);
                    pageByViewer.putIfAbsent(viewer.getUniqueId(), 0);
                    renderPage(top, viewer);
                }));
    }

    private void renderPage(@NotNull Inventory inv, @NotNull Player viewer) {
        List<VoteSnapshot> data = dataByViewer.get(viewer.getUniqueId());
        if (data == null) return;

        int page = pageByViewer.getOrDefault(viewer.getUniqueId(), 0);
        int totalPages = Math.max(1, (int) Math.ceil((double) data.size() / PAGE_SIZE));
        page = Math.max(0, Math.min(page, totalPages - 1));
        pageByViewer.put(viewer.getUniqueId(), page);

        int from = page * PAGE_SIZE;
        int to = Math.min(data.size(), from + PAGE_SIZE);

        // Content slots: rows 1–3, cols 1–7
        int[] grid = {
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34
        };

        // Fill content
        for (int i = 0; i < grid.length; i++) {
            int dataIdx = from + i;
            if (dataIdx < to) {
                inv.setItem(grid[i], renderEntry(dataIdx, data.get(dataIdx)));
            } else {
                inv.setItem(grid[i], ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                        .name(Component.empty()).build());
            }
        }

        // ── Canonical pagination slots (V-10) ────────────────────────
        // prev=47, indicator=49, next=53 — same across Leaderboard, Party
        // and Shop so players never have to relearn where the arrows are.
        if (page > 0) {
            ItemStack prev = ItemBuilder.of(Material.ARROW)
                    .name(name("<gradient:#fde047:#f59e0b>« Previous Page</gradient>"))
                    .lore(List.of(lore("<dark_gray>┃ <gray>Page " + page + " / " + totalPages)))
                    .build();
            tag(prev, "page-prev");
            inv.setItem(47, prev);
        } else {
            inv.setItem(47, filler());
        }

        inv.setItem(49, ItemBuilder.of(Material.PAPER)
                .name(name("<gradient:#a5f3fc:#06b6d4>Page " + (page + 1) + " / " + totalPages + "</gradient>"))
                .lore(List.of(
                        Component.empty(),
                        lore("<dark_gray>┃ <gray>Showing " + (from + 1) + "–" + to + " of " + data.size()),
                        Component.empty()))
                .build());

        if (page + 1 < totalPages) {
            ItemStack next = ItemBuilder.of(Material.ARROW)
                    .name(name("<gradient:#fde047:#f59e0b>Next Page »</gradient>"))
                    .lore(List.of(lore("<dark_gray>┃ <gray>Page " + (page + 2) + " / " + totalPages)))
                    .build();
            tag(next, "page-next");
            inv.setItem(53, next);
        } else {
            inv.setItem(53, filler());
        }
    }

    /**
     * Renders a single leaderboard entry with rank, player info, and vote statistics.
     *
     * @param index the zero-based index in the leaderboard
     * @param entry the vote snapshot data for this player
     * @return the rendered ItemStack
     */
    private @NotNull ItemStack renderEntry(int index, @NotNull VoteSnapshot entry) {
        int rank = index + 1;
        String playerName = entry.playerName() != null ? entry.playerName() : "Unknown";
        String rankGrad = rankGradient(rank);
        String rankSym = rankSymbol(rank);

        Component displayName = name(
                rankGrad + rankSym + " #" + rank + "</gradient> <white>" + playerName);

        String voteBar = buildVoteBar(entry.totalVotes());

        List<Component> itemLore = List.of(
                Component.empty(),
                lore("  <dark_gray>▸</dark_gray> <gray>Total Votes:</gray> <gradient:#86efac:#16a34a>" + entry.totalVotes()),
                lore("  <dark_gray>▸</dark_gray> <gray>Monthly:</gray> <gradient:#a5f3fc:#06b6d4>" + entry.monthlyVotes()),
                lore("  <dark_gray>▸</dark_gray> <gray>Streak:</gray> <gradient:#fde047:#f59e0b>" + entry.currentStreak()),
                lore("  <dark_gray>▸</dark_gray> <gray>Points:</gray> <gradient:#d8b4fe:#9333ea>" + entry.votePoints()),
                Component.empty(),
                lore("  " + voteBar),
                Component.empty()
        );

        if (rank <= 3) {
            // HeadBuilder (unlike ItemBuilder) doesn't strip the default
            // item-meta italic; wrap explicitly so the top-3 names + lore
            // don't render cursive.
            return HeadBuilder.fromPlayer(Bukkit.getOfflinePlayer(entry.playerUuid()))
                    .name(plain(displayName))
                    .lore(plain(itemLore))
                    .build();
        }
        return ItemBuilder.of(rankMaterial(rank))
                .name(displayName)
                .lore(itemLore)
                .build();
    }

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        String id = tagOf(clicked);

        if ("back".equals(id) && overviewView != null) {
            pageByViewer.remove(viewer.getUniqueId());
            dataByViewer.remove(viewer.getUniqueId());
            overviewView.open(viewer);
            return;
        }

        if ("page-prev".equals(id)) {
            pageByViewer.merge(viewer.getUniqueId(), -1, Integer::sum);
            Inventory top = viewer.getOpenInventory().getTopInventory();
            if (top.getHolder() == holder) renderPage(top, viewer);
            return;
        }

        if ("page-next".equals(id)) {
            pageByViewer.merge(viewer.getUniqueId(), 1, Integer::sum);
            Inventory top = viewer.getOpenInventory().getTopInventory();
            if (top.getHolder() == holder) renderPage(top, viewer);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    /**
     * Returns the material for a given rank.
     *
     * @param rank the rank position (1-based)
     * @return the Material to display
     */
    private static Material rankMaterial(int rank) {
        return switch (rank) {
            case 1 -> Material.DIAMOND_BLOCK;
            case 2 -> Material.GOLD_BLOCK;
            case 3 -> Material.IRON_BLOCK;
            case 4, 5 -> Material.EMERALD;
            default -> Material.PAPER;
        };
    }

    /**
     * Returns the MiniMessage gradient string for a given rank.
     *
     * @param rank the rank position (1-based)
     * @return the gradient string
     */
    private static String rankGradient(int rank) {
        return switch (rank) {
            case 1 -> "<gradient:#FFD700:#FFA500>";
            case 2 -> "<gradient:#C0C0C0:#A8A8A8>";
            case 3 -> "<gradient:#CD7F32:#B87333>";
            default -> "<gradient:#86efac:#16a34a>";
        };
    }

    /**
     * Returns the symbol for a given rank.
     *
     * @param rank the rank position (1-based)
     * @return the rank symbol
     */
    private static String rankSymbol(int rank) {
        return switch (rank) {
            case 1 -> "👑";
            case 2 -> "⭐";
            case 3 -> "✦";
            default -> "▸";
        };
    }

    /**
     * Builds a visual vote bar using logarithmic scaling.
     *
     * @param votes the total vote count
     * @return the MiniMessage string for the vote bar
     */
    private static String buildVoteBar(int votes) {
        int bars = 10;
        int filled = votes <= 0 ? 0 : Math.min(bars, Math.max(1, (int) (Math.log10((double) votes + 1) * 3)));
        var sb = new StringBuilder("<dark_gray>[</dark_gray>");
        for (int i = 0; i < bars; i++) {
            sb.append(i < filled ? "<gradient:#fde047:#f59e0b>|</gradient>" : "<dark_gray>|</dark_gray>");
        }
        sb.append("<dark_gray>]</dark_gray> <gray>").append(votes).append(" votes</gray>");
        return sb.toString();
    }

    /**
     * Inventory holder for the leaderboard view.
     */
    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
