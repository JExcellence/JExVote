package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.jextranslate.MessageBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Base for every JExVote chest view. Follows the suite layout: back at slot 0, header at slot 4, filter at
 * slot 8, a 28-slot body (rows 1-4, columns 1-7), pagination at 48 / 50 and close at the first slot of the
 * last row. Empty slots get the black filler pane.
 *
 * <p>Lifecycle: {@link #open(Player)} creates the inventory, calls {@link #render(Inventory, Player)}, fills
 * the gaps and opens it. {@link #rerender(Player)} redraws the open inventory in place, so paging or cycling a
 * filter does not reset the cursor. Clicks are routed by {@link InventoryHolder} identity; close is handled
 * here for every view.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public abstract class VoteBaseView implements Listener {

    protected static final NamespacedKey SLOT_KEY = new NamespacedKey("jexvote", "gui_slot");

    protected static final String TAG_BACK = "back";
    protected static final String TAG_CLOSE = "close";
    protected static final String TAG_PAGE_PREV = "page-prev";
    protected static final String TAG_PAGE_NEXT = "page-next";

    protected static final int SLOT_BACK = 0;
    protected static final int SLOT_HEADER = 4;
    protected static final int SLOT_FILTER = 8;
    protected static final int SLOT_CENTER = VoteLayout.centreSlot();
    protected static final int SLOT_CLOSE = 45;
    protected static final int SLOT_PAGE_PREV = 48;
    protected static final int SLOT_PAGE_NEXT = 50;


    protected abstract @NotNull String title();

    protected abstract int rows();

    protected abstract @NotNull InventoryHolder holder();

    protected abstract void render(@NotNull Inventory inv, @NotNull Player viewer);

    protected abstract void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked);

    /**
     * Click hook with the click type; views with a filter or right-click actions override this one.
     */
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked, @NotNull ClickType type) {
        onClick(viewer, slot, clicked);
    }

    /**
     * Opens a fresh inventory for the viewer.
     *
     * @param viewer the player to open the view for
     */
    public void open(@NotNull Player viewer) {
        Inventory inv = Bukkit.createInventory(holder(), rows() * 9, msg(title()).itemComponent(viewer));
        render(inv, viewer);
        fillGaps(inv);
        viewer.openInventory(inv);
    }

    /**
     * Redraws the view in the viewer's open inventory. Falls back to {@link #open(Player)} when the viewer
     * is looking at something else.
     *
     * @param viewer the player whose view is redrawn
     */
    public void rerender(@NotNull Player viewer) {
        Inventory top = viewer.getOpenInventory().getTopInventory();
        if (top.getHolder() != holder()) {
            open(viewer);
            return;
        }
        top.clear();
        render(top, viewer);
        fillGaps(top);
    }

    /** @return whether the viewer still has this view open (for async callbacks). */
    protected boolean isViewing(@NotNull Player viewer) {
        return viewer.isOnline() && viewer.getOpenInventory().getTopInventory().getHolder() == holder();
    }

    private void fillGaps(@NotNull Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler());
            }
        }
    }

    /**
     * Routes clicks inside this view; every click is cancelled.
     *
     * @param event the inventory click event
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        InventoryHolder owner = event.getInventory().getHolder();
        if (owner == null || owner != holder()) {
            return;
        }
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (!(event.getWhoClicked() instanceof Player viewer) || !isContentItem(clicked)) {
            return;
        }
        if (TAG_CLOSE.equals(tagOf(clicked))) {
            viewer.closeInventory();
            return;
        }
        try {
            onClick(viewer, event.getRawSlot(), clicked, event.getClick());
        } catch (Exception ex) {
            Bukkit.getLogger().log(Level.WARNING, ex, () -> "JExVote view click failed in " + getClass().getSimpleName());
        }
    }

    private static boolean isContentItem(@Nullable ItemStack clicked) {
        return clicked != null && clicked.getType() != Material.AIR
                && clicked.getType() != Material.BLACK_STAINED_GLASS_PANE;
    }

    /**
     * Drops the per-viewer state of a player who left, so the maps do not grow with every visitor.
     *
     * @param event the quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onViewerQuit(@NotNull PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /**
     * Removes what this view remembers about a viewer (page, loaded data). Views with per-viewer state override
     * it.
     *
     * @param viewer the viewer's UUID
     */
    protected void forget(@NotNull UUID viewer) {
        // Views without per-viewer state keep nothing to drop.
    }

    /**
     * Cancels drags into this view.
     *
     * @param event the inventory drag event
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        InventoryHolder owner = event.getInventory().getHolder();
        if (owner != null && owner == holder()) {
            event.setCancelled(true);
        }
    }

    protected @NotNull MessageBuilder msg(@NotNull String key) {
        return VoteCards.msg(key);
    }

    protected @NotNull Component ic(@NotNull String key, @Nullable Player viewer) {
        return VoteCards.ic(viewer, key);
    }

    /**
     * Operator hook: the optional {@code <baseKey>.lore_extra} list is shown as its own block at the end of
     * a card. Missing or empty keys add nothing.
     *
     * @param lore    the card being built
     * @param baseKey the card's base key
     * @param viewer  the viewer
     */
    protected void appendLoreExtra(@NotNull CardLore lore, @NotNull String baseKey, @Nullable Player viewer) {
        MessageBuilder extra = msg(baseKey + ".lore_extra");
        if (extra.exists(viewer)) {
            lore.block(extra.toComponents(viewer).stream()
                    .map(line -> line.decoration(TextDecoration.ITALIC, false))
                    .toList());
        }
    }

    protected @NotNull ItemStack filler() {
        return ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.empty())
                .build();
    }

    /**
     * The back button (slot 0), a dark oak door with a one-line destination.
     *
     * @param viewer         the viewer
     * @param descriptionKey key of the sentence naming where it leads
     * @return the tagged back button
     */
    protected @NotNull ItemStack backButton(@Nullable Player viewer, @NotNull String descriptionKey) {
        ItemStack button = VoteCards.card(Material.DARK_OAK_DOOR, ic(VoteCards.COMMON + "back.name", viewer),
                CardLore.create().block(VoteCards.paragraphOf(viewer, descriptionKey)).build());
        tag(button, TAG_BACK);
        return button;
    }

    /** The close button (first slot of the last row). */
    protected @NotNull ItemStack closeButton(@Nullable Player viewer) {
        ItemStack button = VoteCards.card(Material.BARRIER, ic(VoteCards.COMMON + "close.name", viewer),
                CardLore.create().block(VoteCards.paragraphOf(viewer, VoteCards.COMMON + "close.description")).build());
        tag(button, TAG_CLOSE);
        return button;
    }

    /**
     * Places close and, when {@code backDescriptionKey} is set, the back button.
     *
     * @param inv                the inventory
     * @param viewer             the viewer
     * @param backDescriptionKey key naming the back destination, or {@code null} for a root view
     */
    protected void navBar(@NotNull Inventory inv, @Nullable Player viewer, @Nullable String backDescriptionKey) {
        if (backDescriptionKey != null) {
            inv.setItem(SLOT_BACK, backButton(viewer, backDescriptionKey));
        }
        inv.setItem((rows() - 1) * 9, closeButton(viewer));
    }

    /**
     * Places the previous / next page arrows at 48 / 50 when there is a page in that direction.
     *
     * @param inv    the inventory
     * @param viewer the viewer
     * @param page   zero-based current page
     * @param pages  total page count
     */
    protected void pagination(@NotNull Inventory inv, @Nullable Player viewer, int page, int pages) {
        if (page > 0) {
            inv.setItem(SLOT_PAGE_PREV, pageButton(viewer, "previous", TAG_PAGE_PREV, page, pages));
        }
        if (page + 1 < pages) {
            inv.setItem(SLOT_PAGE_NEXT, pageButton(viewer, "next", TAG_PAGE_NEXT, page + 2, pages));
        }
    }

    private @NotNull ItemStack pageButton(@Nullable Player viewer, @NotNull String direction, @NotNull String navTag,
                                          int targetPage, int pages) {
        String base = VoteCards.COMMON + "page." + direction;
        ItemStack button = VoteCards.card(Material.ARROW, ic(base + ".name", viewer),
                CardLore.create().block(List.of(VoteCards.ic(msg(base + ".lore")
                        .with("page", targetPage).with("pages", pages), viewer))).build());
        tag(button, navTag);
        return button;
    }

    /** Builds the card for one list entry; {@code index} is the entry's position in the whole list. */
    @FunctionalInterface
    protected interface EntryCard<T> {
        @NotNull ItemStack build(int index, @NotNull T entry);
    }

    /**
     * Places one page of a list in the body, centred when the page is not full, and the page arrows.
     *
     * @param inv           the inventory
     * @param viewer        the viewer
     * @param entries       every entry of the list
     * @param requestedPage the zero-based page the viewer asked for
     * @param card          builds the card of one entry
     * @param <T>           entry type
     * @return the page actually shown (clamped)
     */
    protected <T> int renderPage(@NotNull Inventory inv, @Nullable Player viewer, @NotNull List<T> entries,
                                 int requestedPage, @NotNull EntryCard<T> card) {
        int pages = VoteLayout.pageCount(entries.size());
        int page = VoteLayout.clampPage(requestedPage, pages);
        int from = page * VoteLayout.PAGE_SIZE;
        int to = Math.min(entries.size(), from + VoteLayout.PAGE_SIZE);
        int[] slots = VoteLayout.centred(to - from);
        for (int i = 0; i < slots.length; i++) {
            inv.setItem(slots[i], card.build(from + i, entries.get(from + i)));
        }
        pagination(inv, viewer, page, pages);
        return page;
    }

    /**
     * Places a small, fixed set of cards (at most one page) centred in the body.
     *
     * @param inv   the inventory
     * @param cards the cards in reading order
     */
    protected static void placeCentred(@NotNull Inventory inv, @NotNull List<ItemStack> cards) {
        int[] slots = VoteLayout.centred(cards.size());
        for (int i = 0; i < slots.length; i++) {
            inv.setItem(slots[i], cards.get(i));
        }
    }

    /**
     * Tags an item for click routing.
     *
     * @param stack the item
     * @param value the tag
     */
    protected void tag(@NotNull ItemStack stack, @NotNull String value) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(SLOT_KEY, PersistentDataType.STRING, value);
        stack.setItemMeta(meta);
    }

    /**
     * Reads an item's routing tag.
     *
     * @param stack the item
     * @return the tag, or {@code null}
     */
    protected @Nullable String tagOf(@NotNull ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(SLOT_KEY, PersistentDataType.STRING);
    }
}
