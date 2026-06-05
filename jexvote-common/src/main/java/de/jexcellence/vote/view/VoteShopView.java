package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.vote.config.VoteShopItem;
import de.jexcellence.vote.service.VoteShopService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Vote-Token Shop GUI: spend vote points on configured rewards. Each tile shows
 * the reward icon, name and point cost; clicking buys it via {@link VoteShopService}.
 * A header tile shows the player's live point balance.
 *
 * @author JExcellence
 */
public final class VoteShopView extends VoteBaseView {

    private static final String TAG_BACK = "back";
    private static final String TAG_BUY_PREFIX = "buy:";
    private static final int SLOT_POINTS = 4;

    private static final int[] BODY_SLOTS = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final Holder holder = new Holder();
    private final JavaPlugin plugin;
    private final PlatformScheduler scheduler;
    private final VoteShopService shopService;
    private @Nullable VoteRewardsView rewardsView;

    public VoteShopView(@NotNull JavaPlugin plugin, @NotNull VoteShopService shopService) {
        this.plugin = plugin;
        this.scheduler = PlatformScheduler.of(plugin);
        this.shopService = shopService;
    }

    public void setRewardsView(@NotNull VoteRewardsView view) { this.rewardsView = view; }

    @Override protected @NotNull String title()          { return "vote_shop.title"; }
    @Override protected int rows()                        { return 6; }
    @Override protected @NotNull InventoryHolder holder() { return holder; }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {
        glass(inv, Material.PURPLE_STAINED_GLASS_PANE, 0, 8, 45, 53);
        inv.setItem(SLOT_POINTS, pointsIcon(viewer, -1));

        List<VoteShopItem> items = shopService.items();
        if (items.isEmpty()) {
            inv.setItem(22, ItemBuilder.of(Material.BARRIER)
                    .name(ic("vote_shop.empty", viewer))
                    .build());
        } else {
            int idx = 0;
            for (VoteShopItem item : items) {
                if (idx >= BODY_SLOTS.length) {
                    break;
                }
                inv.setItem(BODY_SLOTS[idx++], shopTile(viewer, item));
            }
        }

        if (rewardsView != null) {
            inv.setItem(49, backButton());
        }
    }

    @Override
    public void open(@NotNull Player viewer) {
        super.open(viewer);
        refreshPoints(viewer);
    }

    private @NotNull ItemStack pointsIcon(@NotNull Player viewer, int points) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(msg("vote_shop.points")
                .with("points", points < 0 ? "…" : String.valueOf(points)).itemComponent(viewer));
        lore.add(Component.empty());
        lore.add(ic("vote_shop.hint", viewer));
        return ItemBuilder.of(Material.NETHER_STAR)
                .name(ic("vote_shop.header-name", viewer))
                .glow(points > 0)
                .lore(lore)
                .build();
    }

    private @NotNull ItemStack shopTile(@NotNull Player viewer, @NotNull VoteShopItem item) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(msg("vote_shop.cost").with("cost", String.valueOf(item.cost())).itemComponent(viewer));
        lore.add(Component.empty());
        lore.add(ic("vote_shop.click-buy", viewer));
        ItemStack tile = ItemBuilder.of(item.icon())
                .name(name("<white>" + item.name()))
                .lore(lore)
                .build();
        tag(tile, TAG_BUY_PREFIX + item.id());
        return tile;
    }

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        String tag = tagOf(clicked);
        if (TAG_BACK.equals(tag) && rewardsView != null) {
            rewardsView.open(viewer);
            return;
        }
        if (tag != null && tag.startsWith(TAG_BUY_PREFIX)) {
            String id = tag.substring(TAG_BUY_PREFIX.length());
            shopService.byId(id).ifPresent(item -> buy(viewer, item));
        }
    }

    private void buy(@NotNull Player viewer, @NotNull VoteShopItem item) {
        shopService.purchase(viewer, item).thenAccept(result -> {
            switch (result) {
                case SUCCESS -> msg("vote_shop.bought").prefix()
                        .with("item", item.name())
                        .with("cost", String.valueOf(item.cost())).send(viewer);
                case NOT_ENOUGH_POINTS -> msg("vote_shop.not-enough").prefix()
                        .with("cost", String.valueOf(item.cost())).send(viewer);
                case NO_PROFILE -> msg("vote_shop.no-profile").prefix().send(viewer);
                default -> msg("vote_shop.error").prefix().send(viewer);
            }
            scheduler.runAtEntity(viewer, () -> refreshPoints(viewer));
        }).exceptionally(ex -> {
            plugin.getLogger().fine(() -> "Vote-shop purchase failed: " + ex.getMessage());
            return null;
        });
    }

    /** Loads the player's point balance off-thread and updates the header tile. */
    private void refreshPoints(@NotNull Player viewer) {
        UUID uuid = viewer.getUniqueId();
        shopService.getPoints(uuid).thenAccept(points ->
                scheduler.runAtEntity(viewer, () -> {
                    Inventory top = viewer.getOpenInventory().getTopInventory();
                    if (top.getHolder() != holder) {
                        return;
                    }
                    top.setItem(SLOT_POINTS, pointsIcon(viewer, points));
                })).exceptionally(ex -> {
            plugin.getLogger().fine(() -> "Vote-shop point refresh failed: " + ex.getMessage());
            return null;
        });
    }

    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
