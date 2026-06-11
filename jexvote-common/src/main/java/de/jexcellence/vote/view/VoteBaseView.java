package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.jextranslate.MessageBuilder;
import de.jexcellence.jextranslate.R18nManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Abstract base for all vote GUI views, following the same raw-Bukkit-inventory
 * pattern used by JExOneblock's {@code IslandView}.
 *
 * <p>Lifecycle: {@link #open(Player)} creates a Bukkit inventory, calls
 * {@link #render(Inventory, Player)}, fills empty slots, then opens it.
 * Click/drag events are caught via Bukkit's {@link Listener} interface and
 * routed by {@link InventoryHolder} identity.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public abstract class VoteBaseView implements Listener {

    protected static final MiniMessage MM = MiniMessage.miniMessage();

    protected static final NamespacedKey SLOT_KEY =
            new NamespacedKey("jexvote", "gui_slot");

    /** Shared nav tags routed centrally by {@link #onInventoryClick(InventoryClickEvent)}. */
    protected static final String TAG_BACK = "back";
    protected static final String TAG_CLOSE = "close";

    // ── Abstract hooks ─────────────────────────────────────────────

    protected abstract @NotNull String title();

    protected abstract int rows();

    protected abstract @NotNull InventoryHolder holder();

    protected abstract void render(@NotNull Inventory inv, @NotNull Player viewer);

    protected abstract void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked);

    // ── Lifecycle ──────────────────────────────────────────────────

    /**
     * Opens the GUI inventory for the specified player.
     *
     * @param viewer the player to open the GUI for
     */
    public void open(@NotNull Player viewer) {
        Inventory inv = Bukkit.createInventory(holder(), rows() * 9,
                msg(title()).itemComponent(viewer));
        render(inv, viewer);
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler());
        }
        viewer.openInventory(inv);
    }

    // ── Event handlers ─────────────────────────────────────────────

    /**
     * Handles inventory click events for this view.
     *
     * @param event the inventory click event
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        InventoryHolder owner = event.getInventory().getHolder();
        if (owner == null || owner != holder()) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (clicked.getType() == Material.BLACK_STAINED_GLASS_PANE) return;
        // Close is handled centrally so every view gets it for free.
        if (TAG_CLOSE.equals(tagOf(clicked))) {
            viewer.closeInventory();
            return;
        }
        try {
            onClick(viewer, event.getRawSlot(), clicked);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Handles inventory drag events for this view.
     *
     * @param event the inventory drag event
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        InventoryHolder owner = event.getInventory().getHolder();
        if (owner == null || owner != holder()) return;
        event.setCancelled(true);
    }

    // ── I18n helpers ───────────────────────────────────────────────

    /**
     * Creates a message builder for the given translation key.
     *
     * @param key the translation key
     * @return the message builder
     */
    protected @NotNull MessageBuilder msg(@NotNull String key) {
        return R18nManager.getInstance().msg(key);
    }

    /**
     * Creates a non-italic component from a translation key for the specified
     * player — ready to drop directly into item names or lore lines without
     * inheriting Minecraft's default item-meta italic.
     *
     * <p>The italic-strip happens here (mirroring what {@link #ics(String, Player)}
     * does for multi-line lore) so every name/lore line built via {@code ic()}
     * stays in visual harmony with the rest of the GUI vocabulary.</p>
     *
     * @param key    the translation key
     * @param viewer the player context (may be null)
     * @return the translated, italic-stripped component
     */
    protected @NotNull Component ic(@NotNull String key, @Nullable Player viewer) {
        return msg(key).itemComponent(viewer).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Creates a list of components from a multi-line translation key.
     *
     * @param key    the translation key
     * @param viewer the player context (may be null)
     * @return the list of translated components
     */
    protected @NotNull List<Component> ics(@NotNull String key, @Nullable Player viewer) {
        return msg(key).toComponents(viewer).stream()
                .map(c -> c.decoration(TextDecoration.ITALIC, false))
                .toList();
    }

    /**
     * Strips Minecraft's default item-meta italic from a single component.
     * Use whenever a name/lore is built via a direct
     * {@code msg(...).with(...).itemComponent(viewer)} chain instead of going
     * through {@link #ic(String, Player)} — names with substituted placeholders
     * cannot use {@code ic()} since it does not take the placeholder map.
     *
     * @param component the component to render plainly
     * @return the same component with italic forced off
     */
    protected @NotNull Component plain(@NotNull Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Strips Minecraft's default item-meta italic from each line in a
     * placeholder-substituted lore list. Mirrors what {@link #ics(String, Player)}
     * does for static keys.
     *
     * @param lore the lore components to render plainly
     * @return a new list with italic forced off on every line
     */
    protected @NotNull List<Component> plain(@NotNull List<Component> lore) {
        return lore.stream()
                .map(c -> c.decoration(TextDecoration.ITALIC, false))
                .toList();
    }

    /**
     * Appends any lines from {@code <baseKey>.lore_extra} to {@code lore}, in
     * place. Server owners can populate that list in the translation YAML to
     * add their own bullet points to any tile (e.g. event hints, raffle CTAs)
     * without code changes. When the key is missing or its list is empty this
     * is a no-op, so it's safe to call unconditionally per tile.
     *
     * @param lore    the mutable lore list being assembled
     * @param baseKey the tile's base i18n key (e.g. {@code "vote_overview.identity"})
     * @param viewer  the viewing player (for placeholder resolution)
     */
    protected void appendLoreExtra(@NotNull List<Component> lore,
                                   @NotNull String baseKey,
                                   @Nullable Player viewer) {
        List<Component> extras = ics(baseKey + ".lore_extra", viewer);
        if (extras.isEmpty()) {
            return;
        }
        // Filter out the literal placeholder pattern "{key}.lore_extra" that
        // the translation framework returns when the key is missing entirely
        // (defensive — JExTranslate behavior varies by version).
        boolean placeholder = extras.size() == 1 &&
                extras.get(0).toString().contains(".lore_extra");
        if (!placeholder) {
            lore.addAll(extras);
        }
    }

    // ── Item helpers ───────────────────────────────────────────────

    /**
     * Creates a display name component from MiniMessage.
     *
     * @param mini the MiniMessage string
     * @return the component with italics disabled
     */
    protected static @NotNull Component name(@NotNull String mini) {
        return MM.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Creates a lore component from MiniMessage.
     *
     * @param mini the MiniMessage string
     * @return the component with italics disabled
     */
    protected static @NotNull Component lore(@NotNull String mini) {
        return MM.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    protected @NotNull ItemStack item(@NotNull Material mat,
                                      @NotNull Component itemName,
                                      @NotNull Component... itemLore) {
        ItemBuilder b = ItemBuilder.of(mat)
                .name(itemName)
                .flags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        if (itemLore.length > 0) b.lore(List.of(itemLore));
        return b.build();
    }

    protected @NotNull ItemStack item(@NotNull Material mat,
                                      @NotNull Component itemName,
                                      @NotNull List<Component> itemLore) {
        return ItemBuilder.of(mat)
                .name(itemName)
                .lore(itemLore)
                .flags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
                .build();
    }

    /**
     * Creates a filler item (black stained glass pane).
     *
     * @return the filler ItemStack
     */
    protected @NotNull ItemStack filler() {
        return ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.empty())
                .build();
    }

    /**
     * Creates a back button with the {@value #TAG_BACK} tag (top-left nav slot).
     *
     * <p>Uses {@link Material#DARK_OAK_DOOR} as a "go through to the previous
     * room" metaphor. The name and lore are read from the {@code gui.common.back}
     * i18n keys so server owners can rename/translate without touching code.</p>
     *
     * @return the back button ItemStack
     */
    protected @NotNull ItemStack backButton() {
        ItemStack btn = ItemBuilder.of(Material.DARK_OAK_DOOR)
                .name(ic("gui.common.back-name", null))
                .lore(List.of(ic("gui.common.back-lore", null)))
                .flags(ItemFlag.HIDE_ATTRIBUTES)
                .build();
        tag(btn, TAG_BACK);
        return btn;
    }

    /**
     * Creates a close button with the {@value #TAG_CLOSE} tag (bottom-left nav
     * slot). Closing is routed centrally in {@link #onInventoryClick}.
     *
     * <p>Uses {@link Material#OAK_DOOR} (deliberately a different wood than the
     * back button) as a "leave the building" metaphor. We avoid
     * {@link Material#BARRIER} here on purpose: the red barrier reads as
     * "error / not allowed" which is the wrong message for a normal close
     * action. Name/lore are read from the {@code gui.common.close} i18n keys.</p>
     *
     * @return the close button ItemStack
     */
    protected @NotNull ItemStack closeButton() {
        ItemStack btn = ItemBuilder.of(Material.OAK_DOOR)
                .name(ic("gui.common.close-name", null))
                .lore(List.of(ic("gui.common.close-lore", null)))
                .flags(ItemFlag.HIDE_ATTRIBUTES)
                .build();
        tag(btn, TAG_CLOSE);
        return btn;
    }

    /**
     * Places the standard nav bar: back at the top-left (slot 0) and close at
     * the bottom-left ({@code (rows-1)*9}). Mirrors JExOneblock's IslandView
     * convention so every vote view aligns identically.
     *
     * @param inv      the inventory being rendered
     * @param withBack {@code true} to place the back button (top-level views omit it)
     */
    protected void navBar(@NotNull Inventory inv, boolean withBack) {
        int rows = inv.getSize() / 9;
        if (withBack) {
            inv.setItem(0, backButton());
        }
        inv.setItem((rows - 1) * 9, closeButton());
    }

    /**
     * Fills the 1-wide frame — column 0, column 8, the top row and the bottom
     * row — with a single pane material, leaving the centered interior
     * (cols 1–7 × the inner rows) free for content.
     *
     * @param inv the inventory to frame
     * @param mat the pane material
     */
    protected void frame(@NotNull Inventory inv, @NotNull Material mat) {
        int rows = inv.getSize() / 9;
        ItemStack pane = ItemBuilder.of(mat).name(Component.empty()).build();
        for (int c = 0; c < 9; c++) {
            inv.setItem(c, pane);
            inv.setItem((rows - 1) * 9 + c, pane);
        }
        for (int r = 1; r < rows - 1; r++) {
            inv.setItem(r * 9, pane);
            inv.setItem(r * 9 + 8, pane);
        }
    }

    /**
     * Returns the centered interior slots for this view (rows 1..rows-2 ×
     * columns 1..7), in reading order. Use these for content so the 1-wide
     * frame stays clear.
     *
     * @return the centered body slot indices
     */
    protected int @NotNull [] bodySlots() {
        int rows = rows();
        int[] slots = new int[(rows - 2) * 7];
        int idx = 0;
        for (int r = 1; r <= rows - 2; r++) {
            for (int c = 1; c <= 7; c++) {
                slots[idx++] = r * 9 + c;
            }
        }
        return slots;
    }

    // ── Tag system ─────────────────────────────────────────────────

    /**
     * Tags an ItemStack with a persistent string value for click routing.
     *
     * @param stack the ItemStack to tag
     * @param value the tag value
     */
    protected void tag(@NotNull ItemStack stack, @NotNull String value) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(SLOT_KEY, PersistentDataType.STRING, value);
        stack.setItemMeta(meta);
    }

    /**
     * Retrieves the tag value from an ItemStack.
     *
     * @param stack the ItemStack to read from
     * @return the tag value, or null if not tagged
     */
    protected @Nullable String tagOf(@NotNull ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(SLOT_KEY, PersistentDataType.STRING);
    }

    // ── Shared helpers ─────────────────────────────────────────────

    /**
     * Creates a visual progress bar using MiniMessage.
     *
     * @param current the current progress value
     * @param target  the target value
     * @param bars    the total number of bar segments
     * @return the MiniMessage string for the progress bar
     */
    protected static @NotNull String progressBar(int current, int target, int bars) {
        int filled = target > 0 ? Math.min(bars, (int) ((double) current / target * bars)) : 0;
        var sb = new StringBuilder("<dark_gray>[</dark_gray>");
        for (int i = 0; i < bars; i++) {
            sb.append(i < filled ? "<gradient:#86efac:#16a34a>|</gradient>" : "<dark_gray>|</dark_gray>");
        }
        sb.append("<dark_gray>]</dark_gray>");
        return sb.toString();
    }

    /**
     * Returns the plural suffix for a number (English).
     *
     * @param n the number
     * @return "s" if n != 1, otherwise empty string
     */
    protected static @NotNull String plural(int n) {
        return n != 1 ? "s" : "";
    }
}
