package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
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
    protected String[] layout() {
        return new String[]{
                "         ",
                "  SSSSS  ",
                "         ",
                "  L   K  ",
                "         "
        };
    }

    @Override
    protected int size() {
        return 5;
    }

    @Override
    protected void onRender(@NotNull RenderContext render, @NotNull Player player) {
        List<VoteSite> sites = new ArrayList<>(voteService.getVoteSites().values());

        // ── Stats item (center top) ─────────────────────────────────

        render.slot(0, 4, ItemBuilder.of(Material.DIAMOND)
                .name(MM.deserialize("<gradient:#fde047:#f59e0b><bold>Your Vote Stats</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false))
                .lore(List.of(
                        MM.deserialize("<gray>Loading...</gray>")))
                .build());

        // Load stats async, update slot on main thread
        voteService.getPlayerStats(player.getUniqueId()).thenAccept(stats ->
                scheduler.runAtEntity(player, () ->
                        render.slot(0, 4, ItemBuilder.of(Material.DIAMOND)
                                .name(MM.deserialize("<gradient:#fde047:#f59e0b><bold>Your Vote Stats</bold></gradient>")
                                        .decoration(TextDecoration.ITALIC, false))
                                .lore(List.of(
                                        Component.empty(),
                                        MM.deserialize("  <gray>Total Votes:</gray> <white>" + stats.totalVotes() + "</white>"),
                                        MM.deserialize("  <gray>Monthly Votes:</gray> <white>" + stats.monthlyVotes() + "</white>"),
                                        MM.deserialize("  <gray>Current Streak:</gray> <gradient:#86efac:#16a34a>" + stats.currentStreak() + "</gradient>"),
                                        MM.deserialize("  <gray>Highest Streak:</gray> <gradient:#fde047:#f59e0b>" + stats.highestStreak() + "</gradient>"),
                                        MM.deserialize("  <gray>Vote Points:</gray> <gradient:#d8b4fe:#9333ea>" + stats.votePoints() + "</gradient>"),
                                        Component.empty()
                                ))
                                .build())));

        // ── Vote site items (row 1, layout 'S' slots) ──────────────
        // Layout row 1: "  SSSSS  " = columns 2-6 = slots 11-15
        int[] siteSlots = {11, 12, 13, 14, 15};
        for (int i = 0; i < sites.size() && i < siteSlots.length; i++) {
            VoteSite site = sites.get(i);
            int slot = siteSlots[i];

            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MM.deserialize("  <gray>Service:</gray> <white>" + site.serviceName() + "</white>"));
            lore.add(MM.deserialize("  <gray>Points:</gray> <gradient:#86efac:#16a34a>+" + site.pointsPerVote() + "</gradient>"));
            if (site.voteUrl() != null) {
                lore.add(Component.empty());
                lore.add(MM.deserialize("  <gradient:#fde047:#f59e0b>Click to get the vote link!</gradient>"));
            }

            render.slot(slot, ItemBuilder.of(Material.PAPER)
                    .name(MM.deserialize("<gradient:#a5f3fc:#06b6d4>" + site.displayName() + "</gradient>")
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

        // ── Leaderboard button (row 3 left) ────────────────────────
        render.slot(3, 2, ItemBuilder.of(Material.GOLD_BLOCK)
                .name(MM.deserialize("<gradient:#fde047:#f59e0b><bold>Leaderboard</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false))
                .lore(List.of(
                        Component.empty(),
                        MM.deserialize("  <gray>View the top voters</gray>"),
                        Component.empty(),
                        MM.deserialize("  <gradient:#fde047:#f59e0b>Click to view!</gradient>")))
                .build())
                .onClick(click -> click.openForPlayer(VoteLeaderboardView.class));

        // ── Streak button (row 3 right) ─────────────────────────────
        render.slot(3, 6, ItemBuilder.of(Material.BLAZE_POWDER)
                .name(MM.deserialize("<gradient:#fca5a5:#dc2626><bold>Vote Streaks</bold></gradient>")
                        .decoration(TextDecoration.ITALIC, false))
                .lore(List.of(
                        Component.empty(),
                        MM.deserialize("  <gray>View streak rewards</gray>"),
                        Component.empty(),
                        MM.deserialize("  <gradient:#fca5a5:#dc2626>Click to view!</gradient>")))
                .build())
                .onClick(click -> click.openForPlayer(VoteStreakView.class));
    }
}
