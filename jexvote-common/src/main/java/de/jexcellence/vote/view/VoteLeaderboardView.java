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

        Component displayName = MM.deserialize(
                        rankGradient + "<bold>" + rankSymbol + " #" + rank + "</bold></gradient> <white>" + name + "</white>")
                .decoration(TextDecoration.ITALIC, false);

        // Build progress bar showing this player's votes relative to the #1 spot
        String progressBar = buildVoteBar(entry.totalVotes());

        List<Component> lore = List.of(
                Component.empty(),
                MM.deserialize("  <dark_gray>▸</dark_gray> <gray>Total Votes:</gray> <gradient:#86efac:#16a34a>" + entry.totalVotes() + "</gradient>"),
                MM.deserialize("  <dark_gray>▸</dark_gray> <gray>Monthly:</gray> <gradient:#a5f3fc:#06b6d4>" + entry.monthlyVotes() + "</gradient>"),
                MM.deserialize("  <dark_gray>▸</dark_gray> <gray>Streak:</gray> <gradient:#fde047:#f59e0b>" + entry.currentStreak() + "</gradient>"),
                MM.deserialize("  <dark_gray>▸</dark_gray> <gray>Points:</gray> <gradient:#d8b4fe:#9333ea>" + entry.votePoints() + "</gradient>"),
                Component.empty(),
                MM.deserialize("  " + progressBar),
                Component.empty()
        );

        // Top 3 get player heads, rest get ranked materials
        if (rank <= 3) {
            ItemStack head = HeadBuilder.fromPlayer(Bukkit.getOfflinePlayer(entry.playerUuid()))
                    .name(displayName)
                    .lore(lore)
                    .build();
            builder.withItem(head);
        } else {
            builder.withItem(ItemBuilder.of(rankMaterial(rank))
                    .name(displayName)
                    .lore(lore)
                    .build());
        }
    }

    @Override
    protected void onPaginatedRender(@NotNull RenderContext render, @NotNull Player player) {
        // ── Top row accents ────────────────────────────────────
        render.slot(0, 0, ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(0, 8, ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.empty()).build());

        // ── Header trophy ──────────────────────────────────────
        render.slot(0, 4, ItemBuilder.of(Material.GOLDEN_APPLE)
                .name(MM.deserialize("<gradient:#fde047:#f59e0b><bold>⭐ Top Voters</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false))
                .glow(true)
                .lore(List.of(
                        Component.empty(),
                        MM.deserialize("  <gray>All-time vote leaderboard</gray>"),
                        MM.deserialize("  <gray>Vote daily to climb!</gray>"),
                        Component.empty()))
                .build());

        // ── Legend items ───────────────────────────────────────
        render.slot(0, 2, ItemBuilder.of(Material.DIAMOND)
                .name(MM.deserialize("<gradient:#FFD700:#FFA500><bold>1st Place</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false))
                .lore(List.of(MM.deserialize("<gray>Diamond rank</gray>")))
                .build());

        render.slot(0, 6, ItemBuilder.of(Material.GOLD_INGOT)
                .name(MM.deserialize("<gradient:#C0C0C0:#A8A8A8><bold>2nd Place</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false))
                .lore(List.of(MM.deserialize("<gray>Gold rank</gray>")))
                .build());

        // ── Bottom row accents ─────────────────────────────────
        render.slot(4, 0, ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
        render.slot(4, 8, ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE)
                .name(Component.empty()).build());
    }

    // ── Helpers ─────────────────────────────────────────────────────

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
