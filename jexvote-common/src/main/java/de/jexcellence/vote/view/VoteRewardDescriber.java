package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.reward.impl.CommandReward;
import de.jexcellence.jexplatform.reward.impl.CurrencyReward;
import de.jexcellence.jexplatform.reward.impl.ExperienceReward;
import de.jexcellence.jexplatform.reward.impl.ItemReward;
import de.jexcellence.jexplatform.view.RewardViewHelper;
import de.jexcellence.jextranslate.MessageBuilder;
import de.jexcellence.jextranslate.R18nManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * JExVote-local reward describer. Improves on {@link RewardViewHelper#describe}
 * for the cases the shared renderer shows poorly in the vote GUIs (items,
 * crate-key commands, currency and experience).
 *
 * <p>Every label format lives in the {@code reward_describe.*} i18n keys, so
 * operators can edit the wording, glyphs and colours without touching code.
 * The resolved template (a MiniMessage string with placeholders already
 * substituted) is returned as a fragment that the calling view embeds into its
 * own MiniMessage and parses — so this method serialises the resolved component
 * back to a MiniMessage string. Item names additionally use {@code <lang:…>} so
 * they localise to each player's own client language. Resolution uses the
 * server default locale (the templates are mostly colour + glyph + numbers).
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
            return resolve("reward_describe.item",
                    "amount", item.getAmount(),
                    "material", translationKey(item.getMaterial()));
        }
        if (reward instanceof CommandReward command) {
            return describeCommand(command);
        }
        if (reward instanceof CurrencyReward currency) {
            return resolve("reward_describe.currency",
                    "amount", formatAmount(currency.getAmount()),
                    "unit", prettyUnit(currency.getCurrency()));
        }
        if (reward instanceof ExperienceReward experience) {
            String key = experience.getMode() == ExperienceReward.ExperienceMode.LEVELS
                    ? "reward_describe.experience-levels"
                    : "reward_describe.experience-points";
            return resolve(key, "amount", experience.getAmount());
        }
        return RewardViewHelper.describe(reward);
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
            return resolve("reward_describe.crate-key",
                    "amount", amount,
                    "crate", prettyCrate(crateId));
        }
        return resolve("reward_describe.special");
    }

    /**
     * Resolves a {@code reward_describe.*} template with the given placeholder
     * key/value pairs and serialises it back to a MiniMessage fragment string.
     *
     * @param key the i18n key
     * @param kv  alternating placeholder name/value pairs
     * @return the resolved MiniMessage fragment
     */
    private static @NotNull String resolve(@NotNull String key, @NotNull Object... kv) {
        MessageBuilder builder = R18nManager.getInstance().msg(key);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            builder.with(String.valueOf(kv[i]), kv[i + 1]);
        }
        return MiniMessage.miniMessage().serialize(builder.itemComponent(null));
    }

    private static @NotNull String translationKey(@NotNull String material) {
        try {
            Material mat = Material.matchMaterial(material);
            if (mat != null) {
                return mat.translationKey();
            }
        } catch (Exception ignored) {
            // Fall through to a humanised fallback below
        }
        return "item." + material.toLowerCase(Locale.ROOT);
    }

    /** Formats a currency amount with thousands separators, trimming whole-number decimals. */
    private static @NotNull String formatAmount(double amount) {
        if (amount == Math.floor(amount) && !Double.isInfinite(amount)) {
            return String.format(Locale.US, "%,d", (long) amount);
        }
        return String.format(Locale.US, "%,.2f", amount);
    }

    /** Capitalises a currency id ({@code coins} → {@code Coins}). */
    private static @NotNull String prettyUnit(@NotNull String currency) {
        if (currency.isEmpty()) {
            return "coins";
        }
        return Character.toUpperCase(currency.charAt(0)) + currency.substring(1).toLowerCase(Locale.ROOT);
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
