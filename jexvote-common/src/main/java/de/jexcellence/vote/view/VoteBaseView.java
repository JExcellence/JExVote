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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        InventoryHolder owner = event.getInventory().getHolder();
        if (owner == null || owner != holder()) return;
        event.setCancelled(true);
    }

    // ── I18n helpers ───────────────────────────────────────────────

    protected @NotNull MessageBuilder msg(@NotNull String key) {
        return R18nManager.getInstance().msg(key);
    }

    protected @NotNull Component ic(@NotNull String key, @Nullable Player viewer) {
        return msg(key).itemComponent(viewer);
    }

    protected @NotNull List<Component> ics(@NotNull String key, @Nullable Player viewer) {
        return msg(key).toComponents(viewer).stream()
                .map(c -> c.decoration(TextDecoration.ITALIC, false))
                .toList();
    }

    // ── Item helpers ───────────────────────────────────────────────

    protected static @NotNull Component name(@NotNull String mini) {
        return MM.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

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

    protected @NotNull ItemStack filler() {
        return ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE)
                .name(Component.empty())
                .build();
    }

    protected static void glass(@NotNull Inventory inv, @NotNull Material mat, int... slots) {
        ItemStack pane = ItemBuilder.of(mat).name(Component.empty()).build();
        for (int s : slots) inv.setItem(s, pane);
    }

    protected @NotNull ItemStack backButton() {
        ItemStack btn = ItemBuilder.of(Material.ARROW)
                .name(name("<gray>← Back"))
                .flags(ItemFlag.HIDE_ATTRIBUTES)
                .build();
        tag(btn, "back");
        return btn;
    }

    // ── Tag system ─────────────────────────────────────────────────

    protected void tag(@NotNull ItemStack stack, @NotNull String value) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(SLOT_KEY, PersistentDataType.STRING, value);
        stack.setItemMeta(meta);
    }

    protected @Nullable String tagOf(@NotNull ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(SLOT_KEY, PersistentDataType.STRING);
    }

    // ── Shared helpers ─────────────────────────────────────────────

    protected static @NotNull String progressBar(int current, int target, int bars) {
        int filled = target > 0 ? Math.min(bars, (int) ((double) current / target * bars)) : 0;
        var sb = new StringBuilder("<dark_gray>[</dark_gray>");
        for (int i = 0; i < bars; i++) {
            sb.append(i < filled ? "<gradient:#86efac:#16a34a>|</gradient>" : "<dark_gray>|</dark_gray>");
        }
        sb.append("<dark_gray>]</dark_gray>");
        return sb.toString();
    }

    protected static @NotNull String plural(int n) {
        return n != 1 ? "s" : "";
    }
}
