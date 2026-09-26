package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.view.RewardViewHelper;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.reward.LuckyReward;
import de.jexcellence.vote.service.RewardStatsService;
import de.jexcellence.vote.service.VotePartyService;
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
 * The vote-party prize pool: live party progress in the header, then every pool entry with its odds and how
 * often it has been drawn. Opened from the vote party card in the rewards view.
 *
 * @author JExcellence
 */
public final class VotePartyView extends VotePrizeCatalogView {

    private static final String KEY = "vote_party.";

    private final Holder holder = new Holder();
    private final VoteRewardConfig rewardConfig;
    private final @Nullable VotePartyService party;
    private @Nullable VoteRewardsView rewardsView;

    public VotePartyView(@NotNull VoteRewardConfig rewardConfig,
                         @Nullable VotePartyService party,
                         @NotNull RewardStatsService stats) {
        super(stats, "vote-party-rarity");
        this.rewardConfig = rewardConfig;
        this.party = party;
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
    protected @NotNull List<Prize> prizes() {
        List<Prize> out = new ArrayList<>();
        LuckyReward pool = rewardConfig.getVotePartyPool();
        if (pool != null) {
            VoteLuckyView.addPool(out, pool);
        }
        return out;
    }

    @Override
    protected @NotNull ItemStack header(@NotNull Player viewer, @NotNull List<Prize> prizes) {
        CardLore lore = CardLore.create();
        Component name;
        if (party == null) {
            name = VoteCards.ic(viewer, KEY + "header.name-off");
            lore.block(VoteCards.paragraphOf(viewer, KEY + "header.description-off"));
        } else {
            int current = party.getCurrentVotes();
            int target = party.getTargetVotes();
            name = VoteCards.ic(VoteCards.msg(KEY + "header.name")
                    .with("value", VoteCards.ofTotal(viewer, current, target)), viewer);
            lore.block(VoteCards.paragraphOf(viewer, KEY + "header.description"))
                    .section(VoteCards.section(viewer, "progress"), List.of(
                            VoteCards.bar(viewer, current, target),
                            VoteCards.rowOf(viewer, VoteCards.COMMON + "label.votes",
                                    VoteCards.ofTotal(viewer, current, target)),
                            VoteCards.rowOf(viewer, VoteCards.COMMON + "label.votes-left",
                                    VoteCards.number(viewer, party.getRemainingVotes()))))
                    .section(VoteCards.section(viewer, "every-voter"), fixedRewards(viewer));
        }
        appendLoreExtra(lore, KEY + "header", viewer);
        return VoteCards.card(Material.CAKE, name, lore.build());
    }

    private @NotNull List<Component> fixedRewards(@NotNull Player viewer) {
        List<Component> rows = new ArrayList<>();
        for (AbstractReward reward : rewardConfig.getVotePartyRewards()) {
            for (AbstractReward atomic : RewardViewHelper.flatten(reward)) {
                rows.add(VoteCards.reward(viewer, VoteRewardDescriber.describe(atomic, viewer)));
            }
        }
        return rows;
    }

    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
