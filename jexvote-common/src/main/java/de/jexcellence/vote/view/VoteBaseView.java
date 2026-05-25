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
     * Creates a component from a translation key for the specified player.
     *
     * @param key    the translation key
     * @param viewer the player context (may be null)
     * @return the translated component
     */
    protected @NotNull Component ic(@NotNull String key, @Nullable Player viewer) {
        return msg(key).itemComponent(viewer);
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
     * Fills multiple inventory slots with glass panes.
     *
     * @param inv   the inventory to fill
     * @param mat   the material for the glass panes
     * @param slots the slot indices to fill
     */
    protected static void glass(@NotNull Inventory inv, @NotNull Material mat, int... slots) {
        ItemStack pane = ItemBuilder.of(mat).name(Component.empty()).build();
        for (int s : slots) inv.setItem(s, pane);
    }

    /**
     * Creates a back button with the "back" tag.
     *
     * @return the back button ItemStack
     */
    protected @NotNull ItemStack backButton() {
        ItemStack btn = ItemBuilder.of(Material.ARROW)
                .name(name("<gray>← Back"))
                .flags(ItemFlag.HIDE_ATTRIBUTES)
                .build();
        tag(btn, "back");
        return btn;
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
