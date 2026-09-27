package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.gui.component.FilterHopperButton;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.service.VoteLeaderboardService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Top voters, all time or this month (shared filter). The header shows the viewer's own place; every entry is
 * the player's head with their vote totals. Loads asynchronously and redraws in place.
 *
 * @author JExcellence
 */
public class VoteLeaderboardView extends VoteBaseView {

    private static final String KEY = "vote_leaderboard.";
    private static final String LABEL = VoteCards.COMMON + "label.";
    private static final String PARAM_VALUE = "value";
    private static final int LIMIT = 50;
    private static final String[] MODES = {"all-time", "monthly"};

    private final Holder holder = new Holder();
    private final VoteLeaderboardService leaderboardService;
    private final PlatformScheduler scheduler;
    private final FilterHopperButton modeFilter = new FilterHopperButton("vote-leaderboard-mode", MODES.length);
    private final Map<UUID, Integer> pageByViewer = new ConcurrentHashMap<>();
    private final Map<UUID, List<VoteSnapshot>> dataByViewer = new ConcurrentHashMap<>();

    private @Nullable VoteOverviewView overviewView;

    public VoteLeaderboardView(@NotNull JavaPlugin plugin, @NotNull VoteLeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
        this.scheduler = PlatformScheduler.of(plugin);
    }

    /** Sets the overview view for back navigation. */
    public void setOverviewView(@NotNull VoteOverviewView view) {
        this.overviewView = view;
    }

    @Override protected @NotNull String title() { return KEY + "title"; }
    @Override protected int rows() { return 6; }
    @Override protected @NotNull InventoryHolder holder() { return holder; }

    @Override
    protected void forget(@NotNull UUID viewer) {
        pageByViewer.remove(viewer);
        dataByViewer.remove(viewer);
    }

    @Override
    public void open(@NotNull Player viewer) {
        dataByViewer.remove(viewer.getUniqueId());
        super.open(viewer);
        load(viewer);
    }

    private void load(@NotNull Player viewer) {
        UUID uuid = viewer.getUniqueId();
        CompletableFuture<List<VoteSnapshot>> future = modeFilter.index(uuid) == 1
                ? leaderboardService.getMonthlyTop(LIMIT)
                : leaderboardService.getAllTimeTop(LIMIT);
        future.thenAccept(data -> scheduler.runAtEntity(viewer, () -> {
            if (isViewing(viewer)) {
                dataByViewer.put(uuid, data);
                rerender(viewer);
            }
        }));
    }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {
        navBar(inv, viewer, overviewView == null ? null : KEY + "back");
        int mode = modeFilter.index(viewer.getUniqueId());
        inv.setItem(SLOT_FILTER, filterButton(viewer, mode));
        List<VoteSnapshot> data = dataByViewer.get(viewer.getUniqueId());
        inv.setItem(SLOT_HEADER, header(viewer, mode, data));
        if (data == null) {
            inv.setItem(SLOT_CENTER, VoteCards.notice(viewer, Material.CLOCK, KEY + "pending"));
            return;
        }
        if (data.isEmpty()) {
            inv.setItem(SLOT_CENTER, VoteCards.notice(viewer, Material.PAPER, KEY + "empty"));
            return;
        }
        int page = renderPage(inv, viewer, data, pageByViewer.getOrDefault(viewer.getUniqueId(), 0),
                (index, snapshot) -> entry(viewer, index + 1, snapshot));
        pageByViewer.put(viewer.getUniqueId(), page);
    }

    private @NotNull ItemStack header(@NotNull Player viewer, int mode, @Nullable List<VoteSnapshot> data) {
        String modeLabel = VoteCards.text(viewer, KEY + "mode." + MODES[mode]);
        List<Component> rows = new ArrayList<>();
        rows.add(VoteCards.rowOf(viewer, LABEL + "showing", VoteCards.value(viewer, modeLabel)));
        rows.add(VoteCards.rowOf(viewer, LABEL + "your-place", ownPlace(viewer, data)));
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraphOf(viewer, KEY + "header.description"))
                .section(VoteCards.section(viewer, "now"), rows);
        appendLoreExtra(lore, KEY + "header", viewer);
        return VoteCards.card(Material.GOLDEN_HELMET,
                VoteCards.ic(VoteCards.msg(KEY + "header.name").with(PARAM_VALUE, modeLabel), viewer), lore.build());
    }

    private @NotNull String ownPlace(@NotNull Player viewer, @Nullable List<VoteSnapshot> data) {
        if (data == null) {
            return VoteCards.loading(viewer);
        }
        for (int i = 0; i < data.size(); i++) {
            if (viewer.getUniqueId().equals(data.get(i).playerUuid())) {
                return VoteCards.tone(viewer, "accent", VoteCards.msg(KEY + "place")
                        .with(PARAM_VALUE, i + 1).text(viewer));
            }
        }
        return VoteCards.tone(viewer, "muted", VoteCards.msg(KEY + "not-listed")
                .with(PARAM_VALUE, LIMIT).text(viewer));
    }

    private @NotNull ItemStack filterButton(@NotNull Player viewer, int active) {
        List<String> labels = new ArrayList<>(MODES.length);
        for (String mode : MODES) {
            labels.add(VoteCards.text(viewer, KEY + "mode." + mode));
        }
        ItemStack button = VoteCards.filter(viewer, labels, active);
        tag(button, FilterHopperButton.TAG);
        return button;
    }

    private @NotNull ItemStack entry(@NotNull Player viewer, int rank, @NotNull VoteSnapshot snapshot) {
        String playerName = snapshot.playerName() != null
                ? snapshot.playerName() : VoteCards.text(viewer, KEY + "unknown-player");
        List<Component> rows = List.of(
                VoteCards.rowOf(viewer, LABEL + "votes-total", VoteCards.number(viewer, snapshot.totalVotes())),
                VoteCards.rowOf(viewer, LABEL + "votes-month", VoteCards.number(viewer, snapshot.monthlyVotes())),
                VoteCards.rowOf(viewer, LABEL + "streak", VoteCards.days(viewer, snapshot.currentStreak())),
                VoteCards.rowOf(viewer, LABEL + "vote-points", VoteCards.points(viewer, snapshot.votePoints())));
        CardLore lore = CardLore.create().section(VoteCards.section(viewer, "votes"), rows);
        if (viewer.getUniqueId().equals(snapshot.playerUuid())) {
            lore.block(List.of(VoteCards.ic(viewer, KEY + "you")));
        }
        String nameKey = rank <= 3 ? KEY + "entry.name-podium" : KEY + "entry.name";
        Component name = VoteCards.ic(VoteCards.msg(nameKey).with("rank", rank).with("player", playerName), viewer);
        ItemStack card = VoteCards.card(VoteCards.head(snapshot.playerUuid()), name, lore.build());
        if (rank <= 3) {
            card.setAmount(rank);
        }
        return card;
    }

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        onClick(viewer, slot, clicked, ClickType.LEFT);
    }

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked, @NotNull ClickType type) {
        String tag = tagOf(clicked);
        UUID uuid = viewer.getUniqueId();
        if (TAG_BACK.equals(tag) && overviewView != null) {
            pageByViewer.remove(uuid);
            dataByViewer.remove(uuid);
            overviewView.open(viewer);
        } else if (FilterHopperButton.TAG.equals(tag)) {
            modeFilter.cycle(uuid, !type.isRightClick());
            pageByViewer.remove(uuid);
            dataByViewer.remove(uuid);
            rerender(viewer);
            load(viewer);
        } else if (TAG_PAGE_PREV.equals(tag)) {
            pageByViewer.merge(uuid, -1, Integer::sum);
            rerender(viewer);
        } else if (TAG_PAGE_NEXT.equals(tag)) {
            pageByViewer.merge(uuid, 1, Integer::sum);
            rerender(viewer);
        }
    }

    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
