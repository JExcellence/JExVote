package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.view.PaginatedView;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.service.VoteLeaderboardService;
import me.devnatan.inventoryframework.component.BukkitItemComponentBuilder;
import me.devnatan.inventoryframework.context.Context;
import me.devnatan.inventoryframework.context.RenderContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
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
                "         ",
                "  OOOOO  ",
                "  OOOOO  ",
                "  OOOOO  ",
                "         ",
                "   <p>   "
        };
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
        Material material = rankMaterial(rank);
        String rankGradient = rankGradient(rank);

        Component displayName = MM.deserialize(
                rankGradient + "#" + rank + "</gradient> <white>" + name + "</white>")
                .decoration(TextDecoration.ITALIC, false);

        Component totalLine = Component.text("  Total: ", NamedTextColor.GRAY)
                .append(MM.deserialize("<gradient:#86efac:#16a34a>" + entry.totalVotes() + "</gradient>"));
        Component monthlyLine = Component.text("  Monthly: ", NamedTextColor.GRAY)
                .append(MM.deserialize("<gradient:#a5f3fc:#06b6d4>" + entry.monthlyVotes() + "</gradient>"));
        Component streakLine = Component.text("  Streak: ", NamedTextColor.GRAY)
                .append(MM.deserialize("<gradient:#fde047:#f59e0b>" + entry.currentStreak() + "</gradient>"));

        builder.withItem(createItem(material, displayName, List.of(
                Component.empty(),
                totalLine,
                monthlyLine,
                streakLine
        )));
    }

    @Override
    protected void onPaginatedRender(@NotNull RenderContext render, @NotNull Player player) {
        render.slot(0, 4, createItem(Material.GOLD_BLOCK,
                MM.deserialize("<gradient:#fde047:#f59e0b><bold>Top Voters</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false),
                List.of(MM.deserialize("<gray>All-time vote leaderboard</gray>"))));
    }

    private static Material rankMaterial(int rank) {
        return switch (rank) {
            case 1 -> Material.DIAMOND_BLOCK;
            case 2 -> Material.GOLD_BLOCK;
            case 3 -> Material.IRON_BLOCK;
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
}
