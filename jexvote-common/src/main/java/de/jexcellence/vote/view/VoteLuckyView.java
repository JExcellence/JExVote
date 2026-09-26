package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.gui.style.VoteFormat;
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

/**
 * Lucky Vote prizes: every chance reward and every lucky-pool entry across the default, streak, site and
 * vote-party reward lists, one card each with its real odds. Opened from the Lucky Vote card in the rewards
 * view.
 *
 * @author JExcellence
 */
public final class VoteLuckyView extends VotePrizeCatalogView {

    private static final String KEY = "vote_lucky.";

    private final Holder holder = new Holder();
    private final VoteRewardConfig rewardConfig;
    private @Nullable VoteRewardsView rewardsView;

    public VoteLuckyView(@NotNull VoteRewardConfig rewardConfig, @NotNull RewardStatsService stats) {
        super(stats, "vote-lucky-rarity");
        this.rewardConfig = rewardConfig;
    }

    /** Wires the back button to the rewards view. */
    public void setRewardsView(@NotNull VoteRewardsView view) {
        this.rewardsView = view;
    }

    @Override protected @NotNull String title() { return KEY + "title"; }
    @Override protected @NotNull InventoryHolder holder() { return holder; }
    @Override protected @NotNull String prizePurposeKey() { return KEY + "prize-description"; }
    @Override protected @NotNull String emptyKey() { return KEY + "empty"; }

    @Override
    protected @Nullable String backDescriptionKey() {
        return rewardsView == null ? null : KEY + "back";
    }

    @Override
    protected void openParent(@NotNull Player viewer) {
        if (rewardsView != null) {
            rewardsView.open(viewer);
        }
    }

    @Override
    protected @NotNull ItemStack header(@NotNull Player viewer, @NotNull List<Prize> prizes) {
        List<Component> rows = new ArrayList<>();
        rows.add(VoteCards.rowOf(viewer, VoteCards.COMMON + "label.prizes", VoteCards.number(viewer, prizes.size())));
        if (!prizes.isEmpty()) {
            rows.add(VoteCards.rowOf(viewer, VoteCards.COMMON + "label.best-odds",
                    VoteCards.value(viewer, percentText(viewer, prizes.getFirst().percent()))));
            rows.add(VoteCards.rowOf(viewer, VoteCards.COMMON + "label.rarest",
                    VoteCards.value(viewer, percentText(viewer, prizes.getLast().percent()))));
        }
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraphOf(viewer, KEY + "header.description"))
                .section(VoteCards.section(viewer, "pool"), rows);
        appendLoreExtra(lore, KEY + "header", viewer);
        Component name = VoteCards.ic(VoteCards.msg(KEY + "header.name")
                .with("value", VoteFormat.number(viewer, prizes.size())), viewer);
        return VoteCards.card(Material.RABBIT_FOOT, name, lore.build());
    }

    @Override
    protected @NotNull List<Prize> prizes() {
        List<Prize> out = new ArrayList<>();
        addFrom(out, rewardConfig.getDefaultRewards());
        rewardConfig.getStreakRewards().values().forEach(list -> addFrom(out, list));
        rewardConfig.getSiteRewards().values().forEach(list -> addFrom(out, list));
        addFrom(out, rewardConfig.getVotePartyRewards());
        return out;
    }

    /**
     * Flattens chance rewards and lucky pools into prizes. Shared with the Bedrock form so both list the
     * same prizes.
     *
     * @param out     the list to add to
     * @param rewards a configured reward list
     */
    public static void addFrom(@NotNull List<Prize> out, @NotNull List<AbstractReward> rewards) {
        for (AbstractReward reward : rewards) {
            if (reward instanceof ChanceReward chance) {
                out.add(new Prize(chance.getId(), chance.getChance() * 100.0, chance.getReward()));
            } else if (reward instanceof LuckyReward lucky) {
                addPool(out, lucky);
            }
        }
    }

    /**
     * Adds every entry of a weighted pool with its share of the total weight.
     *
     * @param out  the list to add to
     * @param pool the pool
     */
    public static void addPool(@NotNull List<Prize> out, @NotNull LuckyReward pool) {
        double total = pool.getEntries().stream().mapToDouble(LuckyReward.Entry::weight).sum();
        if (total <= 0.0) {
            return;
        }
        for (LuckyReward.Entry entry : pool.getEntries()) {
            out.add(new Prize(entry.id(), entry.weight() / total * 100.0, entry.reward()));
        }
    }

    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
