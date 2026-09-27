package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.gui.component.FilterHopperButton;
import de.jexcellence.jexplatform.reward.impl.ItemReward;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.config.VoteShopItem;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.service.VoteShopService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The vote shop: spend vote points on configured rewards. The header shows the balance, the shared filter
 * narrows the list (can buy now, crate keys, items, other), and every card shows what it gives, the price and
 * whether the player can afford it. A purchase answers with one chat line and a short title.
 *
 * @author JExcellence
 */
public final class VoteShopView extends VoteBaseView {

    private static final String KEY = "vote_shop.";
    private static final String LABEL = VoteCards.COMMON + "label.";
    private static final String TAG_BUY_PREFIX = "buy:";
    private static final String PARAM_ITEM = "item";
    private static final String PARAM_VALUE = "value";
    private static final String PARAM_MISSING = "missing";
    private static final String[] FILTERS = {"all", "affordable", "keys", "items", "other"};

    private final Holder holder = new Holder();
    private final JavaPlugin plugin;
    private final PlatformScheduler scheduler;
    private final VoteShopService shopService;
    private final FilterHopperButton categoryFilter = new FilterHopperButton("vote-shop-category", FILTERS.length);
    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> cachedBalance = new ConcurrentHashMap<>();

    private @Nullable VoteOverviewView overviewView;

    public VoteShopView(@NotNull JavaPlugin plugin, @NotNull VoteShopService shopService) {
        this.plugin = plugin;
        this.scheduler = PlatformScheduler.of(plugin);
        this.shopService = shopService;
    }

    /** Wires the back button to the vote menu. */
    public void setOverviewView(@NotNull VoteOverviewView view) {
        this.overviewView = view;
    }

    @Override protected @NotNull String title() { return KEY + "title"; }
    @Override protected int rows() { return 6; }
    @Override protected @NotNull InventoryHolder holder() { return holder; }

    @Override
    protected void forget(@NotNull UUID viewer) {
        pageIndex.remove(viewer);
        cachedBalance.remove(viewer);
    }

    @Override
    public void open(@NotNull Player viewer) {
        super.open(viewer);
        refreshBalance(viewer);
    }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {
        navBar(inv, viewer, overviewView == null ? null : KEY + "back-to-menu");
        Integer balance = cachedBalance.get(viewer.getUniqueId());
        List<VoteShopItem> items = shopService.items();
        inv.setItem(SLOT_HEADER, header(viewer, balance, items));
        if (items.isEmpty()) {
            inv.setItem(SLOT_CENTER, VoteCards.notice(viewer, Material.RED_DYE, KEY + "empty"));
            return;
        }
        int filter = categoryFilter.index(viewer.getUniqueId());
        inv.setItem(SLOT_FILTER, filterButton(viewer, filter));
        List<VoteShopItem> shown = items.stream().filter(item -> matches(filter, item, balance)).toList();
        if (shown.isEmpty()) {
            inv.setItem(SLOT_CENTER, VoteCards.notice(viewer, Material.PAPER, KEY + "none-in-filter"));
            return;
        }
        int page = renderPage(inv, viewer, shown, pageIndex.getOrDefault(viewer.getUniqueId(), 0),
                (index, item) -> itemCard(viewer, item, balance));
        pageIndex.put(viewer.getUniqueId(), page);
    }

    private static boolean matches(int filter, @NotNull VoteShopItem item, @Nullable Integer balance) {
        boolean crateKey = VoteRewardDescriber.isCrateKey(item.reward());
        boolean physicalItem = item.reward() instanceof ItemReward;
        return switch (FILTERS[filter]) {
            case "affordable" -> balance == null || balance >= item.cost();
            case "keys" -> crateKey;
            case "items" -> physicalItem;
            case "other" -> !crateKey && !physicalItem;
            default -> true;
        };
    }

    private @NotNull ItemStack filterButton(@NotNull Player viewer, int active) {
        List<String> labels = new ArrayList<>(FILTERS.length);
        for (String filter : FILTERS) {
            labels.add(VoteCards.text(viewer, KEY + "filter." + filter));
        }
        ItemStack button = VoteCards.filter(viewer, labels, active);
        tag(button, FilterHopperButton.TAG);
        return button;
    }

    private @NotNull ItemStack header(@NotNull Player viewer, @Nullable Integer balance,
                                      @NotNull List<VoteShopItem> items) {
        List<Component> rows = new ArrayList<>();
        if (balance == null) {
            rows.add(VoteCards.rowOf(viewer, LABEL + "vote-points", VoteCards.loading(viewer)));
        } else {
            long affordable = items.stream().filter(item -> balance >= item.cost()).count();
            rows.add(VoteCards.rowOf(viewer, LABEL + "vote-points", VoteCards.points(viewer, balance)));
            rows.add(VoteCards.rowOf(viewer, LABEL + "can-buy", VoteCards.ofTotal(viewer, affordable, items.size())));
        }
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraphOf(viewer, KEY + "header.description"))
                .section(VoteCards.section(viewer, "wallet"), rows);
        appendLoreExtra(lore, KEY + "header", viewer);
        String points = balance == null ? VoteCards.loading(viewer) : VoteCards.points(viewer, balance);
        return VoteCards.card(Material.EMERALD,
                VoteCards.ic(VoteCards.msg(KEY + "header.name").with(PARAM_VALUE, points), viewer), lore.build());
    }

    private @NotNull ItemStack itemCard(@NotNull Player viewer, @NotNull VoteShopItem item, @Nullable Integer balance) {
        CardLore lore = CardLore.create();
        if (!item.description().isEmpty()) {
            lore.block(item.description().stream().map(VoteShopView::descriptionLine).toList());
        }
        lore.section(VoteCards.section(viewer, "you-get"),
                        List.of(VoteCards.reward(viewer, VoteRewardDescriber.describe(item.reward(), viewer))))
                .section(VoteCards.section(viewer, "price"), priceRows(viewer, item, balance))
                .block(List.of(stateLine(viewer, item, balance)));
        appendLoreExtra(lore, KEY + "tile", viewer);
        Component name = VoteCards.ic(VoteCards.msg(KEY + "tile.name")
                .with(PARAM_ITEM, item.name())
                .with(PARAM_VALUE, VoteCards.points(viewer, item.cost())), viewer);
        ItemStack card = VoteCards.card(item.icon(), name, lore.build());
        tag(card, TAG_BUY_PREFIX + item.id());
        return card;
    }

    private static @NotNull Component descriptionLine(@NotNull String miniMessage) {
        return MiniMessage.miniMessage().deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
    }

    private @NotNull List<Component> priceRows(@NotNull Player viewer, @NotNull VoteShopItem item,
                                               @Nullable Integer balance) {
        List<Component> rows = new ArrayList<>();
        rows.add(VoteCards.rowOf(viewer, LABEL + "price", VoteCards.points(viewer, item.cost())));
        rows.add(VoteCards.rowOf(viewer, LABEL + "you-have",
                balance == null ? VoteCards.loading(viewer) : VoteCards.points(viewer, balance)));
        return rows;
    }

    private @NotNull Component stateLine(@NotNull Player viewer, @NotNull VoteShopItem item,
                                         @Nullable Integer balance) {
        if (balance == null || balance >= item.cost()) {
            return VoteCards.ic(viewer, KEY + "tile.action");
        }
        return VoteCards.ic(VoteCards.msg(KEY + "tile.short")
                .with(PARAM_MISSING, VoteFormat.number(viewer, (long) item.cost() - balance)), viewer);
    }

    // ── Clicks ────────────────────────────────────────────────────────

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        onClick(viewer, slot, clicked, ClickType.LEFT);
    }

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked, @NotNull ClickType type) {
        String tag = tagOf(clicked);
        if (tag == null) {
            return;
        }
        UUID uuid = viewer.getUniqueId();
        if (TAG_BACK.equals(tag) && overviewView != null) {
            overviewView.open(viewer);
        } else if (FilterHopperButton.TAG.equals(tag)) {
            categoryFilter.cycle(uuid, !type.isRightClick());
            pageIndex.remove(uuid);
            rerender(viewer);
        } else if (TAG_PAGE_PREV.equals(tag) || TAG_PAGE_NEXT.equals(tag)) {
            pageIndex.merge(uuid, TAG_PAGE_PREV.equals(tag) ? -1 : 1, Integer::sum);
            rerender(viewer);
        } else if (tag.startsWith(TAG_BUY_PREFIX)) {
            shopService.byId(tag.substring(TAG_BUY_PREFIX.length())).ifPresent(item -> attemptBuy(viewer, item));
        }
    }

    // ── Purchase ──────────────────────────────────────────────────────

    private void attemptBuy(@NotNull Player viewer, @NotNull VoteShopItem item) {
        int balance = cachedBalance.getOrDefault(viewer.getUniqueId(), -1);
        if (balance >= 0 && balance < item.cost()) {
            sendNotEnough(viewer, item, balance);
            return;
        }
        shopService.purchase(viewer, item).thenAccept(result -> {
            switch (result) {
                case SUCCESS -> scheduler.runAtEntity(viewer, () -> sendReceipt(viewer, item));
                case NOT_ENOUGH_POINTS -> sendNotEnough(viewer, item, Math.max(0, balance));
                case NO_PROFILE -> msg(KEY + "no-profile").prefix().send(viewer);
                case GRANT_FAILED -> msg(KEY + "grant-failed").prefix().send(viewer);
                case BUSY -> {
                    // Intentionally silent: the first click is still being processed.
                }
                default -> msg(KEY + "error").prefix().send(viewer);
            }
            scheduler.runAtEntity(viewer, () -> refreshBalance(viewer));
        }).exceptionally(ex -> {
            plugin.getLogger().fine(() -> "Vote-shop purchase failed: " + ex.getMessage());
            return null;
        });
    }

    private void sendNotEnough(@NotNull Player viewer, @NotNull VoteShopItem item, int balance) {
        msg(KEY + "not-enough").prefix()
                .with(PARAM_ITEM, item.name())
                .with(PARAM_MISSING, VoteFormat.number(viewer, Math.max(0L, (long) item.cost() - balance)))
                .send(viewer);
    }

    private void sendReceipt(@NotNull Player viewer, @NotNull VoteShopItem item) {
        int previous = cachedBalance.getOrDefault(viewer.getUniqueId(), item.cost());
        int after = Math.max(0, previous - item.cost());
        cachedBalance.put(viewer.getUniqueId(), after);
        msg(KEY + "purchase.chat").prefix()
                .with(PARAM_ITEM, item.name())
                .with("cost", VoteFormat.number(viewer, item.cost()))
                .with("balance", VoteFormat.number(viewer, after))
                .send(viewer);
        Component title = msg(KEY + "purchase.title").itemComponent(viewer);
        Component subtitle = msg(KEY + "purchase.subtitle").with(PARAM_ITEM, item.name()).itemComponent(viewer);
        Title.Times times = Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(300));
        viewer.showTitle(Title.title(title, subtitle, times));
    }

    private void refreshBalance(@NotNull Player viewer) {
        UUID uuid = viewer.getUniqueId();
        shopService.getPoints(uuid).thenAccept(points -> scheduler.runAtEntity(viewer, () -> {
            cachedBalance.put(uuid, points);
            if (isViewing(viewer)) {
                rerender(viewer);
            }
        })).exceptionally(ex -> {
            plugin.getLogger().fine(() -> "Vote-shop balance refresh failed: " + ex.getMessage());
            return null;
        });
    }

    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
