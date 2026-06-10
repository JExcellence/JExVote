package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.vote.config.VoteShopItem;
import de.jexcellence.vote.service.VoteShopService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.entity.Player;
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
 * Vote-Token Shop GUI: spend vote points on configured rewards.
 *
 * <p>Each tile renders the actual reward (via {@link VoteRewardDescriber}, with
 * client-localized item names and pretty crate-key labels), the cost, the
 * player's live balance, and an affordability-coloured footer ("Click to buy"
 * in green when affordable, "Need N more points" in red otherwise). Paginated
 * if there are more shop items than the body grid can show. Purchase feedback
 * is layered: the {@link VoteShopService} plays the configured sound + any
 * operator-configured messages, and this view adds a multi-line chat receipt
 * + a title/subtitle on top.</p>
 *
 * <p>Mirrors the V-00 Mythblock-aligned design: door (not barrier) for the
 * empty state, full i18n via {@code vote_shop.*} keys, {@code lore_extra}
 * hooks on every tile.</p>
 *
 * @author JExcellence
 */
public final class VoteShopView extends VoteBaseView {

    private static final String TAG_BUY_PREFIX = "buy:";
    private static final String TAG_PAGE_PREV  = "page-prev";
    private static final String TAG_PAGE_NEXT  = "page-next";

    private static final int SLOT_POINTS    = 4;
    private static final int SLOT_PAGE_PREV = 47;
    private static final int SLOT_PAGE_NEXT = 53;

    /**
     * Body slot grid: 3 inner rows × 7 inner cols = 21 tiles per page.
     */
    private static final int[] BODY_SLOTS = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int PAGE_SIZE = BODY_SLOTS.length;

    private final Holder holder = new Holder();
    private final JavaPlugin plugin;
    private final PlatformScheduler scheduler;
    private final VoteShopService shopService;
    private @Nullable VoteRewardsView rewardsView;

    /** Per-viewer page index, kept tiny (one int per active GUI). */
    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();
    /** Per-viewer cached balance for affordability rendering (refreshed on open + after each buy). */
    private final Map<UUID, Integer> cachedBalance = new ConcurrentHashMap<>();

    public VoteShopView(@NotNull JavaPlugin plugin, @NotNull VoteShopService shopService) {
        this.plugin = plugin;
        this.scheduler = PlatformScheduler.of(plugin);
        this.shopService = shopService;
    }

    /** Wires the rewards view so the shop's back button can route there. */
    public void setRewardsView(@NotNull VoteRewardsView view) {
        this.rewardsView = view;
    }

    @Override protected @NotNull String title()          { return "vote_shop.title"; }
    @Override protected int rows()                        { return 6; }
    @Override protected @NotNull InventoryHolder holder() { return holder; }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {
        frame(inv, Material.PURPLE_STAINED_GLASS_PANE);

        Integer balance = cachedBalance.get(viewer.getUniqueId());
        inv.setItem(SLOT_POINTS, headerTile(viewer, balance == null ? -1 : balance));

        renderBody(inv, viewer);
        navBar(inv, rewardsView != null);
    }

    @Override
    public void open(@NotNull Player viewer) {
        super.open(viewer);
        refreshBalance(viewer);
    }

    // ── Body / pagination ────────────────────────────────────────────────

    private void renderBody(@NotNull Inventory inv, @NotNull Player viewer) {
        List<VoteShopItem> items = shopService.items();
        if (items.isEmpty()) {
            // OAK_DOOR (not BARRIER): "shop being restocked", not "error".
            ItemStack empty = ItemBuilder.of(Material.OAK_DOOR)
                    .name(ic("vote_shop.empty.name", viewer))
                    .lore(emptyLore(viewer))
                    .build();
            // Centre slot of the body grid.
            inv.setItem(22, empty);
            return;
        }

        int totalPages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = clamp(pageIndex.getOrDefault(viewer.getUniqueId(), 0), 0, totalPages - 1);
        pageIndex.put(viewer.getUniqueId(), page);

        int balance = cachedBalance.getOrDefault(viewer.getUniqueId(), -1);
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int slot = BODY_SLOTS[i];
            int idx = start + i;
            if (idx < items.size()) {
                inv.setItem(slot, shopTile(viewer, items.get(idx), balance));
            } else {
                // Fill leftover body slots with the configured filler so they
                // never read as broken-empty.
                inv.setItem(slot, filler());
            }
        }

        if (totalPages > 1) {
            inv.setItem(SLOT_PAGE_PREV, pageButton(viewer, "vote_shop.page-prev", TAG_PAGE_PREV, page, totalPages));
            inv.setItem(SLOT_PAGE_NEXT, pageButton(viewer, "vote_shop.page-next", TAG_PAGE_NEXT, page, totalPages));
        } else {
            inv.setItem(SLOT_PAGE_PREV, null);
            inv.setItem(SLOT_PAGE_NEXT, null);
        }
    }

    private @NotNull List<Component> emptyLore(@NotNull Player viewer) {
        List<Component> lore = new ArrayList<>(ics("vote_shop.empty.lore", viewer));
        appendLoreExtra(lore, "vote_shop.empty", viewer);
        return lore;
    }

    // ── Tile builders ────────────────────────────────────────────────────

    private @NotNull ItemStack headerTile(@NotNull Player viewer, int points) {
        String pointsText = points < 0 ? "…" : String.valueOf(points);
        List<Component> lore = new ArrayList<>(msg("vote_shop.header.lore")
                .with("points", pointsText).toComponents(viewer));
        appendLoreExtra(lore, "vote_shop.header", viewer);
        return ItemBuilder.of(Material.NETHER_STAR)
                .name(ic("vote_shop.header.name", viewer))
                .glow(points > 0)
                .lore(lore)
                .build();
    }

    private @NotNull ItemStack shopTile(@NotNull Player viewer, @NotNull VoteShopItem item, int balance) {
        // Render the reward via the shared describer so item names use the
        // client-translatable name, crate-key commands read as "Dragon Crate
        // Key", currency/XP show with their icon, etc.
        String rewardDesc = VoteRewardDescriber.describe(item.reward());
        String balanceText = balance < 0 ? "…" : String.valueOf(balance);
        boolean canAfford = balance >= item.cost();
        int missing = Math.max(0, item.cost() - balance);

        List<Component> lore = new ArrayList<>(msg("vote_shop.tile.lore")
                .with("item", item.name())
                .with("reward", rewardDesc)
                .with("cost", String.valueOf(item.cost()))
                .with("balance", balanceText)
                .toComponents(viewer));
        // Affordability footer is a separate template so green/red doesn't bleed
        // into the cost line above.
        String footerKey = canAfford ? "vote_shop.tile.footer-buy" : "vote_shop.tile.footer-need";
        lore.add(msg(footerKey).with("missing", String.valueOf(missing)).itemComponent(viewer));
        appendLoreExtra(lore, "vote_shop.tile", viewer);

        ItemStack tile = ItemBuilder.of(item.icon())
                .name(msg("vote_shop.tile.name").with("item", item.name()).itemComponent(viewer))
                .glow(canAfford)
                .lore(lore)
                .build();
        tag(tile, TAG_BUY_PREFIX + item.id());
        return tile;
    }

    private @NotNull ItemStack pageButton(@NotNull Player viewer, @NotNull String keyBase,
                                          @NotNull String navTag, int page, int totalPages) {
        ItemStack btn = ItemBuilder.of(Material.ARROW)
                .name(ic(keyBase + ".name", viewer))
                .lore(List.of(msg(keyBase + ".lore")
                        .with("page", String.valueOf(page + 1))
                        .with("total", String.valueOf(totalPages))
                        .itemComponent(viewer)))
                .build();
        tag(btn, navTag);
        return btn;
    }

    // ── Click routing ────────────────────────────────────────────────────

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        String tag = tagOf(clicked);
        if (tag == null) {
            return;
        }
        if (TAG_BACK.equals(tag) && rewardsView != null) {
            rewardsView.open(viewer);
            return;
        }
        if (TAG_PAGE_PREV.equals(tag)) {
            stepPage(viewer, -1);
            return;
        }
        if (TAG_PAGE_NEXT.equals(tag)) {
            stepPage(viewer, +1);
            return;
        }
        if (tag.startsWith(TAG_BUY_PREFIX)) {
            String id = tag.substring(TAG_BUY_PREFIX.length());
            shopService.byId(id).ifPresent(item -> attemptBuy(viewer, item));
        }
    }

    private void stepPage(@NotNull Player viewer, int delta) {
        UUID uuid = viewer.getUniqueId();
        pageIndex.put(uuid, Math.max(0, pageIndex.getOrDefault(uuid, 0) + delta));
        renderBody(viewer.getOpenInventory().getTopInventory(), viewer);
    }

    // ── Purchase flow ────────────────────────────────────────────────────

    private void attemptBuy(@NotNull Player viewer, @NotNull VoteShopItem item) {
        int balance = cachedBalance.getOrDefault(viewer.getUniqueId(), -1);
        // Optimistic affordability check using the cached balance: avoids a
        // round-trip to the DB on misclicks (and re-checked by the service).
        if (balance >= 0 && balance < item.cost()) {
            msg("vote_shop.not-enough").prefix()
                    .with("missing", String.valueOf(item.cost() - balance))
                    .send(viewer);
            return;
        }
        shopService.purchase(viewer, item).thenAccept(result -> {
            switch (result) {
                case SUCCESS -> sendPurchaseReceipt(viewer, item);
                case NOT_ENOUGH_POINTS -> msg("vote_shop.not-enough").prefix()
                        .with("missing", String.valueOf(
                                Math.max(0, item.cost() - Math.max(0, balance))))
                        .send(viewer);
                case NO_PROFILE -> msg("vote_shop.no-profile").prefix().send(viewer);
                default -> msg("vote_shop.error").prefix().send(viewer);
            }
            scheduler.runAtEntity(viewer, () -> refreshBalance(viewer));
        }).exceptionally(ex -> {
            plugin.getLogger().fine(() -> "Vote-shop purchase failed: " + ex.getMessage());
            return null;
        });
    }

    private void sendPurchaseReceipt(@NotNull Player viewer, @NotNull VoteShopItem item) {
        // Multi-line chat receipt — operator-configured messages from
        // VoteShopService.sendPurchaseMessages stay separate (additive).
        String reward = VoteRewardDescriber.describe(item.reward());
        // Best-effort: post-purchase balance is "previously-cached - cost".
        // Properly updated when refreshBalance() returns; this just makes the
        // receipt show a reasonable number.
        int previous = cachedBalance.getOrDefault(viewer.getUniqueId(), item.cost());
        int after = Math.max(0, previous - item.cost());

        msg("vote_shop.purchase.chat-header").send(viewer);
        msg("vote_shop.purchase.chat-item").with("item", item.name()).send(viewer);
        msg("vote_shop.purchase.chat-reward").with("reward", reward).send(viewer);
        msg("vote_shop.purchase.chat-cost").with("cost", String.valueOf(item.cost())).send(viewer);
        msg("vote_shop.purchase.chat-balance").with("balance", String.valueOf(after)).send(viewer);

        // Title / subtitle layered on top of the service's sound. Short fade
        // so it doesn't block the player's view.
        Component title = msg("vote_shop.purchase.title").itemComponent(viewer);
        Component subtitle = msg("vote_shop.purchase.subtitle")
                .with("item", item.name()).itemComponent(viewer);
        Title.Times times = Title.Times.times(
                Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(300));
        viewer.showTitle(Title.title(title, subtitle, times));
    }

    // ── Balance refresh ──────────────────────────────────────────────────

    private void refreshBalance(@NotNull Player viewer) {
        UUID uuid = viewer.getUniqueId();
        shopService.getPoints(uuid).thenAccept(points ->
                scheduler.runAtEntity(viewer, () -> {
                    cachedBalance.put(uuid, points);
                    Inventory top = viewer.getOpenInventory().getTopInventory();
                    if (top.getHolder() != holder) {
                        return;
                    }
                    top.setItem(SLOT_POINTS, headerTile(viewer, points));
                    renderBody(top, viewer); // re-render to update affordability colors
                })).exceptionally(ex -> {
            plugin.getLogger().fine(() -> "Vote-shop balance refresh failed: " + ex.getMessage());
            return null;
        });
    }

    // ── Utilities ────────────────────────────────────────────────────────

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    /**
     * Static accessor used by views that need to render a reward fragment in
     * MiniMessage form without taking a hard dependency on the package layout.
     */
    @SuppressWarnings("unused")
    private static @NotNull MiniMessage mm() {
        return MiniMessage.miniMessage();
    }

    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
