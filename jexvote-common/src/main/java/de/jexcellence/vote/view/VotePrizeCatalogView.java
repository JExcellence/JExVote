package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.gui.component.FilterHopperButton;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.gui.style.VoteRarityStyle;
import de.jexcellence.vote.service.RewardStatsService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A prize catalog: a header card, the shared rarity filter and one card per prize with its real odds and how
 * often the server has won it. The Lucky Vote and vote-party views both extend this, so their prize cards
 * look the same.
 *
 * @author JExcellence
 * @since 3.3.0
 */
public abstract class VotePrizeCatalogView extends VoteBaseView {

    private static final String KEY = "gui.prize.";
    private static final String PARAM_REWARD = "reward";
    private static final String PARAM_RARITY = "rarity";

    /**
     * One prize with its drop chance.
     *
     * @param id      stats id, {@code null} when the entry has none
     * @param percent drop chance scaled to 0-100
     * @param reward  the reward it pays
     */
    public record Prize(@Nullable String id, double percent, @NotNull AbstractReward reward) {
        /** @return the rarity bucket of this prize. */
        public @NotNull VoteRarityStyle rarity() {
            return VoteRarityStyle.byPercent(percent);
        }
    }

    private final RewardStatsService stats;
    private final FilterHopperButton rarityFilter;
    private final Map<UUID, Integer> pageByViewer = new ConcurrentHashMap<>();

    protected VotePrizeCatalogView(@NotNull RewardStatsService stats, @NotNull String filterKey) {
        this.stats = stats;
        this.rarityFilter = new FilterHopperButton(filterKey, VoteRarityStyle.values().length + 1);
    }

    /** @return every prize of this catalog; sorted by chance by the base class. */
    protected abstract @NotNull List<Prize> prizes();

    /** @return the header card for this catalog. */
    protected abstract @NotNull ItemStack header(@NotNull Player viewer, @NotNull List<Prize> prizes);

    /** @return the key of the sentence on each prize card. */
    protected abstract @NotNull String prizePurposeKey();

    /** @return the key base ({@code .name} / {@code .description}) of the empty-catalog card. */
    protected abstract @NotNull String emptyKey();

    /** @return the key naming where the back button leads, or {@code null} for no back button. */
    protected abstract @Nullable String backDescriptionKey();

    /** Opens the parent view. */
    protected abstract void openParent(@NotNull Player viewer);

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void forget(@NotNull UUID viewer) {
        pageByViewer.remove(viewer);
    }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {
        List<Prize> all = new ArrayList<>(prizes());
        all.sort(Comparator.comparingDouble(Prize::percent).reversed());
        navBar(inv, viewer, backDescriptionKey());
        inv.setItem(SLOT_HEADER, header(viewer, all));
        if (all.isEmpty()) {
            inv.setItem(SLOT_CENTER, VoteCards.notice(viewer, Material.RED_DYE, emptyKey()));
            return;
        }
        int filter = rarityFilter.index(viewer.getUniqueId());
        inv.setItem(SLOT_FILTER, filterButton(viewer, filter));
        List<Prize> shown = all.stream().filter(prize -> matches(filter, prize)).toList();
        if (shown.isEmpty()) {
            inv.setItem(SLOT_CENTER, VoteCards.notice(viewer, Material.PAPER, KEY + "none-in-filter"));
            return;
        }
        renderPrizes(inv, viewer, shown);
    }

    private void renderPrizes(@NotNull Inventory inv, @NotNull Player viewer, @NotNull List<Prize> shown) {
        int page = renderPage(inv, viewer, shown, pageByViewer.getOrDefault(viewer.getUniqueId(), 0),
                (index, prize) -> prizeCard(viewer, prize));
        pageByViewer.put(viewer.getUniqueId(), page);
    }

    private static boolean matches(int filter, @NotNull Prize prize) {
        return filter == 0 || prize.rarity().ordinal() == filter - 1;
    }

    private @NotNull ItemStack filterButton(@NotNull Player viewer, int active) {
        List<String> labels = new ArrayList<>();
        labels.add(VoteCards.text(viewer, KEY + "filter.all"));
        for (VoteRarityStyle rarity : VoteRarityStyle.values()) {
            labels.add(VoteCards.text(viewer, rarity.key()));
        }
        ItemStack button = VoteCards.filter(viewer, labels, active);
        tag(button, FilterHopperButton.TAG);
        return button;
    }

    private @NotNull ItemStack prizeCard(@NotNull Player viewer, @NotNull Prize prize) {
        String reward = VoteRewardDescriber.describe(prize.reward(), viewer);
        String rarity = VoteCards.msg(prize.rarity().key()).miniMessage(viewer);
        long won = prize.id() == null ? 0L : stats.getCount(prize.id());
        List<Component> odds = List.of(
                VoteCards.rowOf(viewer, VoteCards.COMMON + "label.chance",
                        VoteCards.value(viewer, percentText(viewer, prize.percent()))),
                VoteCards.rowOf(viewer, VoteCards.COMMON + "label.rarity", rarity),
                VoteCards.rowOf(viewer, VoteCards.COMMON + "label.won", VoteCards.number(viewer, won)));
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraphOf(viewer, prizePurposeKey()))
                .section(VoteCards.section(viewer, "odds"), odds);
        Component name = VoteCards.ic(VoteCards.msg(KEY + "card.name")
                .with(PARAM_REWARD, reward).with(PARAM_RARITY, rarity), viewer);
        return VoteCards.card(VoteRewardDescriber.icon(prize.reward()), name, lore.build());
    }

    /** A chance as {@code 5%}. */
    protected static @NotNull String percentText(@Nullable Player viewer, double percent) {
        return VoteCards.msg(VoteCards.COMMON + "value.percent")
                .with("value", VoteFormat.percent(viewer, percent)).text(viewer);
    }

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        onClick(viewer, slot, clicked, ClickType.LEFT);
    }

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked, @NotNull ClickType type) {
        String tag = tagOf(clicked);
        UUID uuid = viewer.getUniqueId();
        if (TAG_BACK.equals(tag)) {
            openParent(viewer);
        } else if (FilterHopperButton.TAG.equals(tag)) {
            rarityFilter.cycle(uuid, !type.isRightClick());
            pageByViewer.remove(uuid);
            rerender(viewer);
        } else if (TAG_PAGE_PREV.equals(tag)) {
            pageByViewer.merge(uuid, -1, Integer::sum);
            rerender(viewer);
        } else if (TAG_PAGE_NEXT.equals(tag)) {
            pageByViewer.merge(uuid, 1, Integer::sum);
            rerender(viewer);
        }
    }
}
