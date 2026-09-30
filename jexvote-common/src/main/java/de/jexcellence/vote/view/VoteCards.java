package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.gui.component.FilterHopperButton;
import de.jexcellence.jexplatform.utility.item.HeadBuilder;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.jextranslate.MessageBuilder;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.gui.style.VoteFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The building blocks every JExVote card is made of: wrapped description paragraphs, {@code Label | value}
 * rows, reward lines, value tints, the progress bar, the shared filter button and the item builders that hide
 * vanilla tooltip noise. All text and colour come from the {@code gui.common.*} translation keys, so the Java
 * side only decides which block goes where.
 *
 * @author JExcellence
 * @since 3.3.0
 */
public final class VoteCards {

    /** Root of the shared GUI vocabulary. */
    public static final String COMMON = "gui.common.";

    private static final int WRAP_WIDTH = 34;
    private static final int BAR_CELLS = 20;
    private static final String BAR_CELL = "|";
    private static final String PARAM_VALUE = "value";
    private static final String PARAM_TEXT = "text";
    private static final String PARAM_NAME = "name";

    private VoteCards() {
    }

    // ── Translation access ─────────────────────────────────────────────

    public static @NotNull MessageBuilder msg(@NotNull String key) {
        return R18nManager.getInstance().msg(key);
    }

    /** A non-italic item component for {@code key}. */
    public static @NotNull Component ic(@Nullable Player viewer, @NotNull String key) {
        return ic(msg(key), viewer);
    }

    /** A non-italic item component for a prepared builder (placeholders already set). */
    public static @NotNull Component ic(@NotNull MessageBuilder builder, @Nullable Player viewer) {
        return builder.itemComponent(viewer).decoration(TextDecoration.ITALIC, false);
    }

    /** The plain text of {@code key}, for labels and paragraph wrapping. */
    public static @NotNull String text(@Nullable Player viewer, @NotNull String key) {
        return msg(key).text(viewer);
    }

    // ── Lore blocks ────────────────────────────────────────────────────

    /** Word-wraps a plain sentence into muted lore lines. */
    public static @NotNull List<Component> paragraph(@Nullable Player viewer, @NotNull String plainText) {
        List<Component> lines = new ArrayList<>();
        for (String line : CardLore.wrap(plainText, WRAP_WIDTH)) {
            lines.add(ic(msg(COMMON + "card.line").with(PARAM_TEXT, line), viewer));
        }
        return lines;
    }

    /** Word-wraps the text of a translation key into muted lore lines. */
    public static @NotNull List<Component> paragraphOf(@Nullable Player viewer, @NotNull String key) {
        return paragraph(viewer, text(viewer, key));
    }

    /** A section title line in the section colour. */
    public static @NotNull Component section(@Nullable Player viewer, @NotNull String section) {
        return ic(msg(COMMON + "card.section").with(PARAM_NAME, text(viewer, COMMON + "section." + section)), viewer);
    }

    /** A {@code Label | value} row with an already translated label. */
    public static @NotNull Component row(@Nullable Player viewer, @NotNull String label, @NotNull String valueMini) {
        return ic(msg(COMMON + "card.row").with("label", label).with(PARAM_VALUE, valueMini), viewer);
    }

    /** A {@code Label | value} row whose label is the translation key {@code labelKey}. */
    public static @NotNull Component rowOf(@Nullable Player viewer, @NotNull String labelKey,
                                           @NotNull String valueMini) {
        return row(viewer, text(viewer, labelKey), valueMini);
    }

    /** A {@code + reward} line for reward lists. */
    public static @NotNull Component reward(@Nullable Player viewer, @NotNull String rewardMini) {
        return ic(msg(COMMON + "card.reward").with(PARAM_VALUE, rewardMini), viewer);
    }

    /** A 20-cell progress bar line; always pair it with a numeric row. */
    public static @NotNull Component bar(@Nullable Player viewer, long current, long max) {
        int filled = max <= 0L ? BAR_CELLS : (int) Math.min(BAR_CELLS, Math.max(0L, current) * BAR_CELLS / max);
        return ic(msg(COMMON + "card.bar")
                .with("filled", BAR_CELL.repeat(filled))
                .with("empty", BAR_CELL.repeat(BAR_CELLS - filled)), viewer);
    }

    // ── Values (MiniMessage fragments for row values) ─────────────────

    /** A highlighted plain value. */
    public static @NotNull String value(@Nullable Player viewer, @NotNull String raw) {
        return tone(viewer, "plain", raw);
    }

    /** A value in a semantic tone: {@code plain}, {@code accent}, {@code ok}, {@code bad} or {@code warn}. */
    public static @NotNull String tone(@Nullable Player viewer, @NotNull String tone, @NotNull String raw) {
        return msg(COMMON + "value." + tone).with(PARAM_VALUE, raw).miniMessage(viewer);
    }

    public static @NotNull String number(@Nullable Player viewer, long value) {
        return value(viewer, VoteFormat.number(viewer, value));
    }

    /** {@code have / total} with both numbers formatted. */
    public static @NotNull String ofTotal(@Nullable Player viewer, long have, long total) {
        return msg(COMMON + "value.of-total")
                .with("have", VoteFormat.number(viewer, have))
                .with("total", VoteFormat.number(viewer, total))
                .miniMessage(viewer);
    }

    /** A vote point amount, e.g. {@code 12 points}. */
    public static @NotNull String points(@Nullable Player viewer, long points) {
        return msg(COMMON + "value.points").with(PARAM_VALUE, VoteFormat.number(viewer, points)).miniMessage(viewer);
    }

    /** A day count, e.g. {@code 5 days}. */
    public static @NotNull String days(@Nullable Player viewer, int days) {
        return value(viewer, VoteFormat.days(viewer, days));
    }

    /** The placeholder shown while async data is still loading. */
    public static @NotNull String loading(@Nullable Player viewer) {
        return msg(COMMON + "value.loading").miniMessage(viewer);
    }

    // ── Item builders ──────────────────────────────────────────────────

    /** A card with name and lore; vanilla attribute, enchant and extra tooltip lines are hidden. */
    public static @NotNull ItemStack card(@NotNull ItemStack base, @NotNull Component name,
                                          @NotNull List<Component> lore) {
        return ItemBuilder.from(base).name(name).lore(lore)
                .flags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
                .build();
    }

    public static @NotNull ItemStack card(@NotNull Material icon, @NotNull Component name,
                                          @NotNull List<Component> lore) {
        return card(new ItemStack(icon), name, lore);
    }

    /** A player head with the real skin when it is known (online or cached), without a Mojang lookup. */
    public static @NotNull ItemStack head(@NotNull UUID owner) {
        return HeadBuilder.fromPlayerCached(owner).build();
    }

    /** Adds the enchantment glint without an enchantment line. */
    public static @NotNull ItemStack glint(@NotNull ItemStack stack) {
        stack.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return stack;
    }

    /** A card with a wrapped description and nothing else, for empty or error states. */
    public static @NotNull ItemStack notice(@Nullable Player viewer, @NotNull Material icon, @NotNull String keyBase) {
        return notice(viewer, new ItemStack(icon), keyBase);
    }

    /** A notice card on a prepared base item, e.g. the shared locked icon. */
    public static @NotNull ItemStack notice(@Nullable Player viewer, @NotNull ItemStack base, @NotNull String keyBase) {
        return card(base, ic(viewer, keyBase + ".name"),
                CardLore.create().block(paragraphOf(viewer, keyBase + ".description")).build());
    }

    /**
     * The shared filter button: a hopper minecart named "Filter" with a "Show" block that marks the active option
     * with a filled dot and the others with an empty one, and the left/right-click hint. The caller tags it.
     *
     * @param viewer       the viewer
     * @param optionLabels already translated option labels, in cycle order
     * @param active       index of the active option
     * @return the filter item
     */
    public static @NotNull ItemStack filter(@Nullable Player viewer, @NotNull List<String> optionLabels, int active) {
        List<Component> options = new ArrayList<>(optionLabels.size());
        for (int i = 0; i < optionLabels.size(); i++) {
            String key = i == active ? COMMON + "filter.option-active" : COMMON + "filter.option";
            options.add(ic(msg(key).with(PARAM_NAME, optionLabels.get(i)), viewer));
        }
        return card(FilterHopperButton.ICON, ic(viewer, COMMON + "filter.name"),
                CardLore.create()
                        .section(ic(viewer, COMMON + "filter.title"), options)
                        .block(List.of(ic(viewer, COMMON + "filter.action")))
                        .build());
    }
}
