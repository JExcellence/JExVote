package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.gui.style.MythCurrencyFormat;
import de.jexcellence.jexplatform.gui.style.MythCurrencyFormat.CurrencyType;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.reward.impl.CommandReward;
import de.jexcellence.jexplatform.reward.impl.CurrencyReward;
import de.jexcellence.jexplatform.reward.impl.ExperienceReward;
import de.jexcellence.jexplatform.reward.impl.ItemReward;
import de.jexcellence.jexplatform.view.RewardViewHelper;
import de.jexcellence.jextranslate.MessageBuilder;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.config.CurrencyDisplay;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.reward.ChanceReward;
import de.jexcellence.vote.reward.LuckyReward;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Turns a reward into one short line for lore, chat and Bedrock forms: {@code 2x Diamond},
 * {@code 1x Void Crate key}, {@code +5 island radius}, or the coin / crystal icon with the amount.
 *
 * <p>Every label is a {@code reward_describe.*} translation template; this class only picks the template and
 * fills it. {@link #describe} returns a MiniMessage fragment for Java surfaces (currency as the coin / crystal
 * icon when {@link CurrencyDisplay#icons()} is on, otherwise the currency name),
 * {@link #describeText} spells the currency out for Bedrock forms, where the resource-pack icon does not
 * render. {@link #icon} picks the material that best shows the reward in a GUI.
 *
 * @author JExcellence
 */
public final class VoteRewardDescriber {

    private static final String KEY = "reward_describe.";
    private static final String AMOUNT = "amount";
    private static final String TYPE_CURRENCY = "currency";
    private static final String CRATE_WORD = "crate";

    private static final AtomicReference<CurrencyDisplay> DISPLAY = new AtomicReference<>(CurrencyDisplay.PLAIN);

    private VoteRewardDescriber() {
    }

    /**
     * Sets how currency rewards are written (icons or names). Called on start and on reload.
     *
     * @param display the resolved currency display
     */
    public static void configure(@NotNull CurrencyDisplay display) {
        DISPLAY.set(display);
    }

    /** Describes a reward in the server's default language (currency as icon). */
    public static @NotNull String describe(@NotNull AbstractReward reward) {
        return describe(reward, null);
    }

    /** Describes a reward in the viewer's language (currency as icon). */
    public static @NotNull String describe(@NotNull AbstractReward reward, @Nullable Player viewer) {
        return describe(reward, viewer, true);
    }

    /** Describes a reward with the currency written out, for surfaces that cannot show the icon font. */
    public static @NotNull String describeText(@NotNull AbstractReward reward, @Nullable Player viewer) {
        return describe(reward, viewer, false);
    }

    /**
     * Describes what a pool actually paid out: the prize, marked as a lucky win.
     *
     * @param won    the entry the draw selected
     * @param viewer the winner, for their language
     * @return a description of the prize
     */
    public static @NotNull String describeLuckyWin(@NotNull LuckyReward.Entry won, @Nullable Player viewer) {
        return template("lucky-win").with("reward", describe(won.reward(), viewer)).miniMessage(viewer);
    }

    private static @NotNull String describe(@NotNull AbstractReward reward, @Nullable Player viewer, boolean icons) {
        if (reward instanceof ItemReward item) {
            return template("item")
                    .with(AMOUNT, VoteFormat.number(viewer, item.getAmount()))
                    .with("material", translationKey(item.getMaterial()))
                    .miniMessage(viewer);
        }
        if (reward instanceof CommandReward command) {
            return describeCommand(command, viewer);
        }
        if (reward instanceof CurrencyReward currency) {
            return describeCurrency(currency, viewer, icons);
        }
        if (reward instanceof ExperienceReward experience) {
            String key = experience.getMode() == ExperienceReward.ExperienceMode.LEVELS
                    ? "experience-levels" : "experience-points";
            return template(key).with(AMOUNT, VoteFormat.number(viewer, experience.getAmount())).miniMessage(viewer);
        }
        if (reward instanceof LuckyReward lucky) {
            return template("lucky-pool").with("count", lucky.getEntries().size()).miniMessage(viewer);
        }
        if (reward instanceof ChanceReward chance) {
            return template("chance")
                    .with("chance", VoteFormat.percent(viewer, chance.getChance() * 100.0))
                    .with("reward", describe(chance.getReward(), viewer, icons))
                    .miniMessage(viewer);
        }
        return RewardViewHelper.describe(reward);
    }

    private static @NotNull String describeCurrency(@NotNull CurrencyReward currency, @Nullable Player viewer,
                                                    boolean icons) {
        String amount = VoteFormat.decimal(viewer, currency.getAmount());
        CurrencyType type = MythCurrencyFormat.fromIdentifier(currency.getCurrency());
        CurrencyDisplay display = DISPLAY.get();
        if (icons && display.icons() && type != null
                && type != CurrencyType.SEASON_POINTS && type != CurrencyType.TOKENS) {
            return MythCurrencyFormat.compact(type, amount, viewer);
        }
        return template(TYPE_CURRENCY).with(AMOUNT, amount)
                .with("unit", currencyName(currency.getCurrency(), viewer)).miniMessage(viewer);
    }

    /**
     * The display name of a currency id: the operator name from {@code display.currency-names}, then the
     * {@code reward_describe.unit.<id>} translation, then the id itself with a capital first letter.
     *
     * @param currencyId the currency id of a reward
     * @param viewer     the viewer, for their language
     * @return the name
     */
    public static @NotNull String currencyName(@NotNull String currencyId, @Nullable Player viewer) {
        String configured = DISPLAY.get().nameOf(currencyId);
        if (configured != null) {
            return configured;
        }
        MessageBuilder unit = R18nManager.getInstance().msg(KEY + "unit." + currencyId.toLowerCase(Locale.ROOT));
        return unit.exists(viewer) ? unit.text(viewer) : prettyWord(currencyId);
    }

    private static @NotNull String describeCommand(@NotNull CommandReward command, @Nullable Player viewer) {
        String describe = command.getDescribe();
        if (describe != null && !describe.isBlank()) {
            return resolveDescribe(describe, viewer);
        }
        CommandShape shape = CommandShape.of(command.getCommand());
        return switch (shape.kind()) {
            case CRATE_KEY -> template("crate-key")
                    .with(AMOUNT, shape.amount()).with(CRATE_WORD, prettyCrate(shape.subject())).miniMessage(viewer);
            case ISLAND_RADIUS -> template("island-radius").with(AMOUNT, shape.amount()).miniMessage(viewer);
            case FLY_COUPON -> template("fly-coupon")
                    .with("minutes", shape.subject()).with("count", shape.amount()).miniMessage(viewer);
            default -> template("special").miniMessage(viewer);
        };
    }

    /**
     * The material that shows a reward best in a GUI: the item itself, a tripwire hook for crate keys, grass
     * for island radius, a feather for flight, a gold nugget or amethyst shard for currency.
     *
     * @param reward the reward
     * @return the icon material
     */
    public static @NotNull Material icon(@NotNull AbstractReward reward) {
        if (reward instanceof ChanceReward chance) {
            return icon(chance.getReward());
        }
        if (reward instanceof ItemReward item) {
            Material material = Material.matchMaterial(item.getMaterial());
            return material != null && material.isItem() ? material : Material.CHEST;
        }
        if (reward instanceof CurrencyReward currency) {
            return MythCurrencyFormat.fromIdentifier(currency.getCurrency()) == CurrencyType.CRYSTALS
                    ? Material.AMETHYST_SHARD : Material.GOLD_NUGGET;
        }
        if (reward instanceof CommandReward command) {
            return switch (CommandShape.of(command.getCommand()).kind()) {
                case CRATE_KEY -> Material.TRIPWIRE_HOOK;
                case ISLAND_RADIUS -> Material.GRASS_BLOCK;
                case FLY_COUPON -> Material.FEATHER;
                default -> Material.NETHER_STAR;
            };
        }
        if (reward instanceof LuckyReward) {
            return Material.RABBIT_FOOT;
        }
        return RewardViewHelper.toViewEntry(reward).icon();
    }

    /**
     * Whether the reward hands out a crate key (a recognised crate-key command).
     *
     * @param reward the reward
     * @return {@code true} for crate keys
     */
    public static boolean isCrateKey(@NotNull AbstractReward reward) {
        return reward instanceof CommandReward command
                && CommandShape.of(command.getCommand()).kind() == CommandKind.CRATE_KEY;
    }

    private static @NotNull MessageBuilder template(@NotNull String name) {
        return R18nManager.getInstance().msg(KEY + name);
    }

    /**
     * Resolves an operator {@code describe} value: a MiniMessage literal when it carries a tag, a translation
     * key when it looks like one (dotted, no spaces), otherwise the plain text.
     */
    private static @NotNull String resolveDescribe(@NotNull String describe, @Nullable Player viewer) {
        if (describe.indexOf('<') >= 0) {
            return describe;
        }
        if (describe.indexOf(' ') < 0 && describe.indexOf('.') > 0) {
            return R18nManager.getInstance().msg(describe).miniMessage(viewer);
        }
        return describe;
    }

    private static @NotNull String translationKey(@NotNull String material) {
        Material mat = Material.matchMaterial(material);
        if (mat != null) {
            return mat.translationKey();
        }
        return "item.minecraft." + material.toLowerCase(Locale.ROOT);
    }

    private static @NotNull String prettyWord(@NotNull String word) {
        if (word.isEmpty()) {
            return word;
        }
        return Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * Turns a crate id such as {@code dragon_crate} or {@code DragonCrate} into {@code Dragon}; the template
     * adds the word "Crate". A trailing "crate" in the id is dropped so it is not doubled.
     */
    private static @NotNull String prettyCrate(@NotNull String crateId) {
        String spaced = crateId.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ');
        StringBuilder label = new StringBuilder();
        for (String word : spaced.trim().split("\\s+")) {
            if (!word.isEmpty() && !word.equalsIgnoreCase(CRATE_WORD)) {
                if (!label.isEmpty()) {
                    label.append(' ');
                }
                label.append(prettyWord(word));
            }
        }
        return label.toString();
    }

    /** The recognised shapes of reward commands used across the suite. */
    private enum CommandKind { CRATE_KEY, ISLAND_RADIUS, FLY_COUPON, OTHER }

    /**
     * A parsed reward command.
     *
     * @param kind    what the command grants
     * @param subject crate id, or fly-coupon minutes
     * @param amount  how many
     */
    private record CommandShape(@NotNull CommandKind kind, @NotNull String subject, @NotNull String amount) {

        private static final CommandShape OTHER = new CommandShape(CommandKind.OTHER, "", "1");

        static @NotNull CommandShape of(@Nullable String raw) {
            String[] tokens = raw == null ? new String[0] : raw.trim().split("\\s+");
            if (isCrateGive(tokens) || isVirtualKeyGive(tokens)) {
                return new CommandShape(CommandKind.CRATE_KEY, tokens[4], tokens.length >= 6 ? tokens[5] : "1");
            }
            if (tokens.length >= 4 && isOneblock(tokens, "grant-radius")) {
                return new CommandShape(CommandKind.ISLAND_RADIUS, "", tokens[3]);
            }
            if (tokens.length >= 5 && isOneblock(tokens, "flycoupon")) {
                return new CommandShape(CommandKind.FLY_COUPON, tokens[3], tokens[4]);
            }
            return OTHER;
        }

        private static boolean isCrateGive(@NotNull String[] tokens) {
            return tokens.length >= 5
                    && (tokens[0].equalsIgnoreCase(CRATE_WORD) || tokens[0].equalsIgnoreCase("crates"))
                    && tokens[1].equalsIgnoreCase("give") && tokens[2].equalsIgnoreCase("key");
        }

        private static boolean isVirtualKeyGive(@NotNull String[] tokens) {
            return tokens.length >= 5
                    && (tokens[0].equalsIgnoreCase("ac") || tokens[0].equalsIgnoreCase("advancedcrates"))
                    && tokens[1].equalsIgnoreCase("virtualkey") && tokens[2].equalsIgnoreCase("give");
        }

        private static boolean isOneblock(@NotNull String[] tokens, @NotNull String subcommand) {
            return tokens[0].equalsIgnoreCase("jexoneblock") && tokens[1].equalsIgnoreCase(subcommand);
        }
    }
}
