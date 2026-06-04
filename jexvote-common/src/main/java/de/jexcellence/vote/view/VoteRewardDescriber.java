package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.reward.impl.CommandReward;
import de.jexcellence.jexplatform.reward.impl.ItemReward;
import de.jexcellence.jexplatform.view.RewardViewHelper;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * JExVote-local reward describer. Improves on {@link RewardViewHelper#describe}
 * for the two cases the shared renderer shows poorly in the vote GUIs:
 *
 * <ul>
 *   <li><b>item</b> — uses the client-translatable item name
 *       ({@code <lang:…>}) instead of a lowercased English material, so
 *       "8× diamond" localises to each player's own client language.</li>
 *   <li><b>command</b> — a {@code crate give key … <crate>} command renders as a
 *       friendly "Dragon Crate Key" label instead of the raw command line.</li>
 * </ul>
 *
 * Everything else delegates to {@link RewardViewHelper#describe}.
 *
 * @author JExcellence
 */
public final class VoteRewardDescriber {

    private static final String CRATE_SUFFIX = "_crate";

    private VoteRewardDescriber() {
        // Utility class — no instances
    }

    /**
     * Returns a short, single-line MiniMessage description of one reward.
     */
    public static @NotNull String describe(@NotNull AbstractReward reward) {
        if (reward instanceof ItemReward item) {
            return describeItem(item);
        }
        if (reward instanceof CommandReward command) {
            return describeCommand(command);
        }
        return RewardViewHelper.describe(reward);
    }

    private static @NotNull String describeItem(@NotNull ItemReward item) {
        return "<gradient:#93c5fd:#2563eb>" + item.getAmount()
                + "× <lang:" + translationKey(item.getMaterial()) + "></gradient>";
    }

    private static @NotNull String translationKey(@NotNull String material) {
        try {
            Material mat = Material.matchMaterial(material);
            if (mat != null) {
                return mat.translationKey();
            }
        } catch (Throwable ignored) {
            // Fall through to a humanised fallback below
        }
        return "item." + material.toLowerCase(Locale.ROOT);
    }

    private static @NotNull String describeCommand(@NotNull CommandReward command) {
        String raw = command.getCommand();
        String[] tokens = raw == null ? new String[0] : raw.trim().split("\\s+");
        boolean isCrateKey = tokens.length >= 5
                && (tokens[0].equalsIgnoreCase("crate") || tokens[0].equalsIgnoreCase("crates"))
                && tokens[1].equalsIgnoreCase("give")
                && tokens[2].equalsIgnoreCase("key");
        if (isCrateKey) {
            String crateId = tokens[4];
            String amount = tokens.length >= 6 ? tokens[5] : "1";
            return "<gradient:#a5f3fc:#06b6d4>" + amount + "× " + prettyCrate(crateId) + " Key</gradient>";
        }
        return "<gradient:#d8b4fe:#9333ea>✦ Special Reward</gradient>";
    }

    /**
     * Turns a crate identifier ({@code dragon_crate}) into a readable label
     * ({@code Dragon Crate}).
     */
    private static @NotNull String prettyCrate(@NotNull String crateId) {
        String base = crateId.toLowerCase(Locale.ROOT);
        if (base.endsWith(CRATE_SUFFIX)) {
            base = base.substring(0, base.length() - CRATE_SUFFIX.length());
        }
        StringBuilder label = new StringBuilder();
        for (String word : base.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (label.length() > 0) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.append(" Crate").toString();
    }
}
