package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.jexplatform.view.RewardViewHelper;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.gui.style.VoteRarityStyle;
import de.jexcellence.vote.reward.ChanceReward;
import de.jexcellence.vote.reward.LuckyReward;
import de.jexcellence.vote.service.RewardStatsService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dedicated detail view for Lucky Vote / Vote Jackpot outcomes (V-11.7).
 *
 * <p>The old approach crammed every chance/lucky entry into a single tile's
 * lore in {@code VoteRewardsView}, producing a long unreadable list. This view
 * gives every outcome its own tile — material picked from the wrapped reward
 * via {@link RewardViewHelper#toViewEntry}, name in the rarity colour from
 * {@link VoteRarityStyle#byChance(double)}, lore showing the exact chance,
 * the lifetime drop count and a one-line reward description.</p>
 *
 * <p>Layout matches the V-00 design system: 1-wide PURPLE frame, header at
 * slot 4, 21-tile body grid, canonical pagination at 47 / 49 / 53.</p>
 *
 * @author JExcellence
 */
public final class VoteLuckyView extends VoteBaseView {

    private static final String TAG_PAGE_PREV = "page-prev";
    private static final String TAG_PAGE_NEXT = "page-next";

    private static final int SLOT_HEADER         = 4;
    private static final int SLOT_PAGE_PREV      = 47;
    private static final int SLOT_PAGE_INDICATOR = 49;
    private static final int SLOT_PAGE_NEXT      = 53;
    private static final Material FRAME_MATERIAL = Material.MAGENTA_STAINED_GLASS_PANE;

    /** Body slot grid: 3 inner rows × 7 inner cols = 21 entries per page. */
    private static final int[] BODY_SLOTS = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int PAGE_SIZE = BODY_SLOTS.length;

    /**
     * Flat row used internally so chance + lucky-pool entries render the same
     * way. {@code id} drives the lifetime-stats lookup ({@code null} for entries
     * that didn't opt into stats tracking).
     */
    private record Entry(@Nullable String id, double chance, @NotNull AbstractReward reward) {}

    private final Holder holder = new Holder();
    private final VoteRewardConfig rewardConfig;
    private final RewardStatsService stats;
    private @Nullable VoteRewardsView rewardsView;

    /** Per-viewer page index. */
    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();

    public VoteLuckyView(@NotNull VoteRewardConfig rewardConfig,
                         @NotNull RewardStatsService stats) {
        this.rewardConfig = rewardConfig;
        this.stats = stats;
    }

    /** Wires the back button to the rewards view (set post-construction). */
    public void setRewardsView(@NotNull VoteRewardsView view) {
        this.rewardsView = view;
    }

    @Override protected @NotNull String title()          { return "vote_lucky.title"; }
    @Override protected int rows()                        { return 6; }
    @Override protected @NotNull InventoryHolder holder() { return holder; }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {
        frame(inv, FRAME_MATERIAL);

        List<Entry> entries = collectEntries();
        inv.setItem(SLOT_HEADER, headerTile(viewer, entries.size()));

        if (entries.isEmpty()) {
            // Empty-state: door, not barrier (V-00 convention).
            inv.setItem(22, ItemBuilder.of(Material.OAK_DOOR)
                    .name(ic("vote_lucky.empty.name", viewer))
                    .lore(ics("vote_lucky.empty.lore", viewer))
                    .build());
            navBar(inv, rewardsView != null);
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) PAGE_SIZE));
        int page = clampPage(pageIndex.getOrDefault(viewer.getUniqueId(), 0), totalPages);
        pageIndex.put(viewer.getUniqueId(), page);

        int start = page * PAGE_SIZE;
        for (int i = 0; i < BODY_SLOTS.length; i++) {
            int idx = start + i;
            if (idx < entries.size()) {
                inv.setItem(BODY_SLOTS[i], entryTile(viewer, entries.get(idx)));
            } else {
                inv.setItem(BODY_SLOTS[i], filler());
            }
        }

        if (totalPages > 1) {
            inv.setItem(SLOT_PAGE_PREV, pageButton(viewer, "vote_lucky.page-prev", TAG_PAGE_PREV));
            inv.setItem(SLOT_PAGE_INDICATOR, pageIndicator(viewer, page, totalPages, entries.size()));
            inv.setItem(SLOT_PAGE_NEXT, pageButton(viewer, "vote_lucky.page-next", TAG_PAGE_NEXT));
        } else {
            // Restore the frame pane so the bottom border stays unbroken
            // (mirrors the V-11.6 fix in VoteShopView).
            ItemStack framePane = ItemBuilder.of(FRAME_MATERIAL).name(Component.empty()).build();
            inv.setItem(SLOT_PAGE_PREV, framePane);
            inv.setItem(SLOT_PAGE_INDICATOR, framePane);
            inv.setItem(SLOT_PAGE_NEXT, framePane);
        }

        navBar(inv, rewardsView != null);
    }

    // ── Tile builders ────────────────────────────────────────────────────

    private @NotNull ItemStack headerTile(@NotNull Player viewer, int count) {
        List<Component> lore = new ArrayList<>(plain(msg("vote_lucky.header.lore")
                .with("count", String.valueOf(count)).toComponents(viewer)));
        appendLoreExtra(lore, "vote_lucky.header", viewer);
        return ItemBuilder.of(Material.SPONGE)
                .name(ic("vote_lucky.header.name", viewer))
                .glow(count > 0)
                .lore(lore)
                .build();
    }

    private @NotNull ItemStack entryTile(@NotNull Player viewer, @NotNull Entry entry) {
        // Pull the icon material from the underlying reward (works for items,
        // currencies, commands etc.) and tint the name with the rarity bucket.
        RewardViewHelper.ViewEntry view = RewardViewHelper.toViewEntry(entry.reward());
        VoteRarityStyle rarity = VoteRarityStyle.byChance(entry.chance() / 100.0);
        String rewardDesc = VoteRewardDescriber.describe(entry.reward());
        long won = entry.id() == null ? 0L : stats.getCount(entry.id());

        List<Component> lore = new ArrayList<>(plain(msg("vote_lucky.entry.lore")
                .with("reward", rewardDesc)
                .with("chance", formatPercent(entry.chance()))
                .with("won", String.valueOf(won))
                .with("rarity", rarity.display(rarity.name()))
                .toComponents(viewer)));
        appendLoreExtra(lore, "vote_lucky.entry", viewer);

        return ItemBuilder.of(view.icon())
                .name(rarity.component(rewardDesc))
                .lore(lore)
                .build();
    }

    private @NotNull ItemStack pageButton(@NotNull Player viewer, @NotNull String keyBase, @NotNull String navTag) {
        ItemStack btn = ItemBuilder.of(Material.ARROW)
                .name(ic(keyBase, viewer))
                .build();
        tag(btn, navTag);
        return btn;
    }

    private @NotNull ItemStack pageIndicator(@NotNull Player viewer, int page, int totalPages, int total) {
        int from = page * PAGE_SIZE;
        int to = Math.min(total, from + PAGE_SIZE);
        return ItemBuilder.of(Material.PAPER)
                .name(msg("vote_lucky.page-indicator.name")
                        .with("page", String.valueOf(page + 1))
                        .with("total", String.valueOf(totalPages))
                        .itemComponent(viewer))
                .lore(plain(List.of(msg("vote_lucky.page-indicator.lore")
                        .with("from", String.valueOf(from + 1))
                        .with("to", String.valueOf(to))
                        .with("count", String.valueOf(total))
                        .itemComponent(viewer))))
                .build();
    }

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
        UUID uuid = viewer.getUniqueId();
        if (TAG_PAGE_PREV.equals(tag)) {
            pageIndex.put(uuid, Math.max(0, pageIndex.getOrDefault(uuid, 0) - 1));
            open(viewer);
        } else if (TAG_PAGE_NEXT.equals(tag)) {
            pageIndex.put(uuid, pageIndex.getOrDefault(uuid, 0) + 1);
            open(viewer);
        }
    }

    // ── Entry collection ────────────────────────────────────────────────

    /**
     * Walks every configured reward list (default / streak / site / vote-party)
     * and flattens chance + lucky-pool outcomes into a single ordered list,
     * highest chance first. Same de-duplication as the old in-place lore
     * renderer used.
     */
    private @NotNull List<Entry> collectEntries() {
        List<Entry> out = new ArrayList<>();
        addFrom(out, rewardConfig.getDefaultRewards());
        rewardConfig.getStreakRewards().values().forEach(list -> addFrom(out, list));
        rewardConfig.getSiteRewards().values().forEach(list -> addFrom(out, list));
        addFrom(out, rewardConfig.getVotePartyRewards());
        out.sort((a, b) -> Double.compare(b.chance(), a.chance()));
        return out;
    }

    private static void addFrom(@NotNull List<Entry> out, @NotNull List<AbstractReward> rewards) {
        for (AbstractReward reward : rewards) {
            if (reward instanceof ChanceReward cr) {
                out.add(new Entry(cr.getId(), cr.getChance() * 100.0, cr.getReward()));
            } else if (reward instanceof LuckyReward lr) {
                double total = lr.getEntries().stream().mapToDouble(LuckyReward.Entry::weight).sum();
                if (total <= 0.0) {
                    continue;
                }
                for (LuckyReward.Entry e : lr.getEntries()) {
                    out.add(new Entry(e.id(), e.weight() / total * 100.0, e.reward()));
                }
            }
        }
    }

    private static int clampPage(int page, int totalPages) {
        if (page < 0) {
            return 0;
        }
        return Math.min(page, totalPages - 1);
    }

    private static @NotNull String formatPercent(double chance) {
        if (chance == Math.floor(chance)) {
            return String.valueOf((long) chance);
        }
        return String.format(Locale.ROOT, "%.1f", chance);
    }

    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
