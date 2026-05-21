package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.utility.item.HeadBuilder;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.jexplatform.view.PaginatedView;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.service.VoteLeaderboardService;
import me.devnatan.inventoryframework.component.BukkitItemComponentBuilder;
import me.devnatan.inventoryframework.context.Context;
import me.devnatan.inventoryframework.context.RenderContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class VoteLeaderboardView extends PaginatedView<VoteSnapshot> {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final VoteLeaderboardService leaderboardService;

    public VoteLeaderboardView(@NotNull VoteLeaderboardService leaderboardService) {
        super(VoteOverviewView.class);
        this.leaderboardService = leaderboardService;
    }

    @Override
    protected String translationKey() {
        return "vote_leaderboard";
    }

    @Override
    protected String[] layout() {
        return new String[]{
                "XXXXXXXXX",
                "XOOOOOOOX",
                "XOOOOOOOX",
                "XOOOOOOOX",
                "XXXXXXXXX",
                "   <p>   "
        };
    }

    @Override
    protected Material fillerMaterial() {
        return Material.BLACK_STAINED_GLASS_PANE;
    }

    @Override
    protected @NotNull CompletableFuture<List<VoteSnapshot>> loadData(@NotNull Context ctx) {
        return leaderboardService.getAllTimeTop(50);
    }

    @Override
    protected void renderItem(@NotNull Context context,
                              @NotNull BukkitItemComponentBuilder builder,
                              int index,
                              @NotNull VoteSnapshot entry) {
        int rank = index + 1;
        String name = entry.playerName() != null ? entry.playerName() : "Unknown";
        String rankGradient = rankGradient(rank);
        String rankSymbol = rankSymbol(rank);

        Component displayName = name(
                rankGradient + "<bold>" + rankSymbol + " #" + rank + "</bold></gradient> <white>" + name + "</white>");

        // Build progress bar showing this player's votes relative to the #1 spot
        String progressBar = buildVoteBar(entry.totalVotes());

        List<Component> itemLore = List.of(
                Component.empty(),
                lore("  <dark_gray>▸</dark_gray> <gray>Total Votes:</gray> <gradient:#86efac:#16a34a>" + entry.totalVotes()),
                lore("  <dark_gray>▸</dark_gray> <gray>Monthly:</gray> <gradient:#a5f3fc:#06b6d4>" + entry.monthlyVotes()),
                lore("  <dark_gray>▸</dark_gray> <gray>Streak:</gray> <gradient:#fde047:#f59e0b>" + entry.currentStreak()),
                lore("  <dark_gray>▸</dark_gray> <gray>Points:</gray> <gradient:#d8b4fe:#9333ea>" + entry.votePoints()),
                Component.empty(),
                lore("  " + progressBar),
                Component.empty()
        );

        // Top 3 get player heads, rest get ranked materials
        if (rank <= 3) {
            ItemStack head = HeadBuilder.fromPlayer(Bukkit.getOfflinePlayer(entry.playerUuid()))
                    .name(displayName)
                    .lore(itemLore)
                    .build();
            builder.withItem(head);
        } else {
            builder.withItem(ItemBuilder.of(rankMaterial(rank))
                    .name(displayName)
                    .lore(itemLore)
                    .build());
        }
    }

    @Override
    protected void onPaginatedRender(@NotNull RenderContext render, @NotNull Player player) {
        // ── Top row accents (absolute slots) ───────────────────
        render.slot(0, ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(8, ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.empty()).build());

        // ── Header trophy (slot 4) ─────────────────────────────
        render.slot(4, ItemBuilder.of(Material.GOLDEN_APPLE)
                .name(name("<gradient:#fde047:#f59e0b><bold>⭐ Top Voters</bold></gradient>"))
                .glow(true)
                .lore(List.of(
                        Component.empty(),
                        lore("  <gray>All-time vote leaderboard"),
                        lore("  <gray>Vote daily to climb!"),
                        Component.empty()))
                .build());

        // ── Legend items ───────────────────────────────────────
        render.slot(2, ItemBuilder.of(Material.DIAMOND)
                .name(name("<gradient:#FFD700:#FFA500><bold>1st Place</bold></gradient>"))
                .lore(List.of(lore("<gray>Diamond rank")))
                .build());

        render.slot(6, ItemBuilder.of(Material.GOLD_INGOT)
                .name(name("<gradient:#C0C0C0:#A8A8A8><bold>2nd Place</bold></gradient>"))
                .lore(List.of(lore("<gray>Gold rank")))
                .build());

        // ── Bottom row accents (row 4 = slots 36, 44) ──────────
        render.slot(36, ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(44, ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static Component name(String mini) {
        return MM.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    private static Component lore(String mini) {
        return MM.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    private static Material rankMaterial(int rank) {
        return switch (rank) {
            case 1 -> Material.DIAMOND_BLOCK;
            case 2 -> Material.GOLD_BLOCK;
            case 3 -> Material.IRON_BLOCK;
            case 4, 5 -> Material.EMERALD;
            default -> Material.PAPER;
        };
    }

    private static String rankGradient(int rank) {
        return switch (rank) {
            case 1 -> "<gradient:#FFD700:#FFA500>";
            case 2 -> "<gradient:#C0C0C0:#A8A8A8>";
            case 3 -> "<gradient:#CD7F32:#B87333>";
            default -> "<gradient:#86efac:#16a34a>";
        };
    }

    private static String rankSymbol(int rank) {
        return switch (rank) {
            case 1 -> "👑";
            case 2 -> "⭐";
            case 3 -> "✦";
            default -> "▸";
        };
    }

    private static String buildVoteBar(int votes) {
        int bars = 10;
        // Log-scale bar: 1 bar per order of magnitude, then linear within
        int filled = votes <= 0 ? 0 : Math.min(bars, Math.max(1, (int) (Math.log10(votes + 1) * 3)));
        StringBuilder sb = new StringBuilder("<dark_gray>[</dark_gray>");
        for (int i = 0; i < bars; i++) {
            if (i < filled) {
                sb.append("<gradient:#fde047:#f59e0b>|</gradient>");
            } else {
                sb.append("<dark_gray>|</dark_gray>");
            }
        }
        sb.append("<dark_gray>]</dark_gray> <gray>").append(votes).append(" votes</gray>");
        return sb.toString();
    }
}
