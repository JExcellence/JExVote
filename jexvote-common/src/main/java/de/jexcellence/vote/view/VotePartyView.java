package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.reward.impl.CommandReward;
import de.jexcellence.jexplatform.reward.impl.CurrencyReward;
import de.jexcellence.jexplatform.reward.impl.ExperienceReward;
import de.jexcellence.jexplatform.reward.impl.ItemReward;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
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
import java.util.Locale;

/**
 * Dedicated Vote-Party reward catalog: lists every entry in the weighted party
 * pool with its drop chance and lifetime pull count (from {@link RewardStatsService}),
 * plus a header showing live party progress. Opened from the party icon in
 * {@link VoteRewardsView}.
 *
 * @author JExcellence
 */
public final class VotePartyView extends VoteBaseView {

    private static final String TAG_BACK = "back";

    /** Body slots (rows 2–5, edges excluded) — fits up to 21 pool entries. */
    private static final int[] BODY_SLOTS = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final Holder holder = new Holder();
    private final VoteRewardConfig rewardConfig;
    private final @Nullable VotePartyService party;
    private final RewardStatsService stats;
    private @Nullable VoteRewardsView rewardsView;

    public VotePartyView(@NotNull VoteRewardConfig rewardConfig,
                         @Nullable VotePartyService party,
                         @NotNull RewardStatsService stats) {
        this.rewardConfig = rewardConfig;
        this.party = party;
        this.stats = stats;
    }

    /** Wires the back button to return to the rewards overview. */
    public void setRewardsView(@NotNull VoteRewardsView view) { this.rewardsView = view; }

    @Override protected @NotNull String title()          { return "vote_party.title"; }
    @Override protected int rows()                        { return 6; }
    @Override protected @NotNull InventoryHolder holder() { return holder; }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {
        glass(inv, Material.YELLOW_STAINED_GLASS_PANE, 0, 8, 45, 53);
        inv.setItem(4, headerIcon(viewer));

        LuckyReward pool = rewardConfig.getVotePartyPool();
        if (pool == null || pool.getEntries().isEmpty()) {
            inv.setItem(22, ItemBuilder.of(Material.BARRIER)
                    .name(ic("vote_party.empty", viewer))
                    .build());
        } else {
            double total = pool.getEntries().stream().mapToDouble(LuckyReward.Entry::weight).sum();
            int idx = 0;
            for (LuckyReward.Entry entry : pool.getEntries()) {
                if (idx >= BODY_SLOTS.length) {
                    break;
                }
                inv.setItem(BODY_SLOTS[idx++], entryIcon(viewer, entry, total));
            }
        }

        if (rewardsView != null) {
            inv.setItem(49, backButton());
        }
    }

    private @NotNull ItemStack headerIcon(@NotNull Player viewer) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        if (party == null) {
            lore.add(ic("vote_party.disabled", viewer));
        } else {
            int current = party.getCurrentVotes();
            int target = party.getTargetVotes();
            lore.add(msg("vote_party.progress")
                    .with("current", String.valueOf(current))
                    .with("target", String.valueOf(target)).itemComponent(viewer));
            lore.add(msg("vote_party.remaining")
                    .with("remaining", String.valueOf(party.getRemainingVotes())).itemComponent(viewer));
            lore.add(lore("  " + progressBar(current, target, 20)));
        }
        lore.add(Component.empty());
        lore.add(ic("vote_party.header.lore", viewer));
        return ItemBuilder.of(Material.TOTEM_OF_UNDYING)
                .name(ic("vote_party.header.name", viewer))
                .glow(party != null)
                .lore(lore)
                .build();
    }

    private @NotNull ItemStack entryIcon(@NotNull Player viewer, @NotNull LuckyReward.Entry entry, double total) {
        double chance = total > 0 ? (entry.weight() / total) * 100.0 : 0.0;
        long won = entry.id() != null ? stats.getCount(entry.id()) : 0L;

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(msg("vote_party.entry-chance").with("chance", fmt(chance)).itemComponent(viewer));
        lore.add(msg("vote_party.entry-won").with("won", String.valueOf(won)).itemComponent(viewer));

        return ItemBuilder.of(iconFor(entry.reward()))
                .name(name("<white>" + VoteRewardDescriber.describe(entry.reward())))
                .lore(lore)
                .build();
    }

    /** Resolves a representative GUI icon for a pool entry's reward type. */
    private @NotNull Material iconFor(@NotNull AbstractReward reward) {
        if (reward instanceof ItemReward item) {
            Material material = Material.matchMaterial(item.getMaterial());
            return material != null ? material : Material.CHEST;
        }
        if (reward instanceof CommandReward) {
            return Material.TRIPWIRE_HOOK; // crate keys are command rewards
        }
        if (reward instanceof CurrencyReward) {
            return Material.GOLD_INGOT;
        }
        if (reward instanceof ExperienceReward) {
            return Material.EXPERIENCE_BOTTLE;
        }
        return Material.NETHER_STAR;
    }

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        if (TAG_BACK.equals(tagOf(clicked)) && rewardsView != null) {
            rewardsView.open(viewer);
        }
    }

    private static @NotNull String fmt(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
