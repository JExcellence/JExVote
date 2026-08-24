package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.reward.impl.CommandReward;
import de.jexcellence.jexplatform.reward.impl.CurrencyReward;
import de.jexcellence.jexplatform.reward.impl.ExperienceReward;
import de.jexcellence.jexplatform.reward.impl.ItemReward;
import de.jexcellence.jexplatform.view.RewardViewHelper;
import de.jexcellence.vote.reward.ChanceReward;
import de.jexcellence.vote.reward.LuckyReward;
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
 * own MiniMessage and parses - so this method serialises the resolved component
 * back to a MiniMessage string. Item names additionally use {@code <lang:…>} so
 * they localise to each player's own client language. Resolution uses the
 * server default locale (the templates are mostly colour + glyph + numbers).
 *
 * @author JExcellence
 */
public final class VoteRewardDescriber {

    private static final String AMOUNT = "amount";

    private VoteRewardDescriber() {
        // Utility class - no instances
    }

    /**
     * Returns a short, single-line MiniMessage description of one reward.
     */
    public static @NotNull String describe(@NotNull AbstractReward reward) {
        if (reward instanceof ItemReward item) {
            return resolve("reward_describe.item",
                    AMOUNT, item.getAmount(),
                    "material", translationKey(item.getMaterial()));
        }
        if (reward instanceof CommandReward command) {
            return describeCommand(command);
        }
        if (reward instanceof CurrencyReward currency) {
            return resolve("reward_describe.currency",
                    AMOUNT, formatAmount(currency.getAmount()),
                    "unit", prettyUnit(currency.getCurrency()));
        }
        if (reward instanceof ExperienceReward experience) {
            String key = experience.getMode() == ExperienceReward.ExperienceMode.LEVELS
                    ? "reward_describe.experience-levels"
                    : "reward_describe.experience-points";
            return resolve(key, AMOUNT, experience.getAmount());
        }
        // JExVote's own pool types. Without these the shared renderer falls through to
        // its default branch and prints the bare type id - a jackpot rendered as the
        // grey word "lucky", which is what a player saw in the reward list.
        if (reward instanceof LuckyReward lucky) {
            return resolve("reward_describe.lucky-pool",
                    "count", lucky.getEntries().size());
        }
        if (reward instanceof ChanceReward chance) {
            return resolve("reward_describe.chance",
                    "chance", formatChance(chance.getChance()),
                    "reward", describe(chance.getReward()));
        }
        return RewardViewHelper.describe(reward);
    }

    /**
     * Describes what a pool actually paid out, rather than the pool itself.
     *
     * <p>A catalogue wants "one of 8 jackpot prizes"; somebody reading what they just
     * received wants the prize. Same type, two different questions, so two methods.
     *
     * @param won the entry the draw selected
     * @return a description of the prize, marked as a jackpot win
     */
    public static @NotNull String describeLuckyWin(@NotNull LuckyReward.Entry won) {
        return resolve("reward_describe.lucky-win", "reward", describe(won.reward()));
    }

    /** Trims a 0-1 chance to a readable percentage. */
    private static @NotNull String formatChance(double chance) {
        double percent = chance * 100.0;
        if (percent == Math.floor(percent)) {
            return String.valueOf((long) percent);
        }
        return String.format(Locale.US, "%.1f", percent);
    }

    private static @NotNull String describeCommand(@NotNull CommandReward command) {
        // 1. Operator-supplied description always wins (MiniMessage literal or i18n key).
        String describe = command.getDescribe();
        if (describe != null && !describe.isBlank()) {
            return resolveDescribe(describe);
        }
        // 2. Auto-recognise the command shapes actually used across the suite so
        //    unannotated rewards still read as their grant, not "Special Reward".
        String raw = command.getCommand();
        String[] t = raw == null ? new String[0] : raw.trim().split("\\s+");

        // /crate give key <player> <crate> [amount]
        if (t.length >= 5 && (t[0].equalsIgnoreCase("crate") || t[0].equalsIgnoreCase("crates"))
                && t[1].equalsIgnoreCase("give") && t[2].equalsIgnoreCase("key")) {
            return crateKey(t[4], t.length >= 6 ? t[5] : "1");
        }
        // AdvancedCrates: ac|advancedcrates virtualkey give <player> <crate> [amount]
        if (t.length >= 5 && (t[0].equalsIgnoreCase("ac") || t[0].equalsIgnoreCase("advancedcrates"))
                && t[1].equalsIgnoreCase("virtualkey") && t[2].equalsIgnoreCase("give")) {
            return crateKey(t[4], t.length >= 6 ? t[5] : "1");
        }
        // jexoneblock grant-radius <player> <amount>
        if (t.length >= 4 && t[0].equalsIgnoreCase("jexoneblock") && t[1].equalsIgnoreCase("grant-radius")) {
            return resolve("reward_describe.island-radius", AMOUNT, t[3]);
        }
        // jexoneblock flycoupon <player> <minutes> <count>
        if (t.length >= 5 && t[0].equalsIgnoreCase("jexoneblock") && t[1].equalsIgnoreCase("flycoupon")) {
            return resolve("reward_describe.fly-coupon", "minutes", t[3], "count", t[4]);
        }
        return resolve("reward_describe.special");
    }

    private static @NotNull String crateKey(@NotNull String crateId, @NotNull String amount) {
        return resolve("reward_describe.crate-key", AMOUNT, amount, "crate", prettyCrate(crateId));
    }

    /**
     * Resolves an operator {@code describe} value: a MiniMessage literal when it
     * carries a tag, an i18n key when it looks like one (dotted, no spaces),
     * otherwise the plain text as-is.
     */
    private static @NotNull String resolveDescribe(@NotNull String describe) {
        if (describe.indexOf('<') >= 0) {
            return describe;
        }
        if (describe.indexOf(' ') < 0 && describe.indexOf('.') > 0) {
            return resolve(describe);
        }
        return describe;
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
     * Turns a crate identifier into a readable {@code <Name> Crate} label, handling both
     * {@code dragon_crate} (snake_case) and {@code DragonCrate} (camelCase, as used
     * by AdvancedCrates virtual keys). A trailing "crate" word is dropped so it is
     * not duplicated by the appended suffix.
     */
    private static @NotNull String prettyCrate(@NotNull String crateId) {
        // Split camelCase boundaries and underscores into spaces.
        String spaced = crateId
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replace('_', ' ');
        StringBuilder label = new StringBuilder();
        for (String word : spaced.trim().split("\\s+")) {
            if (word.isEmpty() || word.equalsIgnoreCase("crate")) {
                continue;
            }
            if (label.length() > 0) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return label.append(" Crate").toString();
    }
}
