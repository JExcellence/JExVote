package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.gui.style.LockedIcon;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.settings.VoteSettingOption;
import de.jexcellence.vote.settings.VoteSettings;
import de.jexcellence.vote.settings.VoteSettingsService;
import de.jexcellence.vote.settings.VoteSettingsService.Availability;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The player's vote settings ({@code /vote settings}): back at 0, header at 4 with every setting as a
 * {@code Label | Value} row, one toggle card per option in the body and close at 45. A toggle card lists its
 * values with a filled dot for the active one and an empty dot for the others; left-click selects the next value,
 * right-click the previous one, like the shared Filter hopper. An option whose server feature is off is a red dye
 * without an action; the Discord option only appears with JExDiscord installed and the account linked.
 *
 * @author JExcellence
 * @since 3.4.0
 */
public final class VoteSettingsView extends VoteBaseView {

    private static final String KEY = "vote_settings.";
    private static final String OPTION = KEY + "option.";
    private static final String VALUE = KEY + "value.";
    private static final String TAG_SETTING_PREFIX = "setting:";
    private static final String PARAM_VALUE = "value";
    private static final String NAME = ".name";
    private static final Map<VoteSettingOption, Material> ICONS = icons();

    private final Holder holder = new Holder();
    private final VoteSettingsService settings;
    private final VoteConfig config;
    private final PlatformScheduler scheduler;
    private @Nullable VoteOverviewView overviewView;

    public VoteSettingsView(@NotNull JavaPlugin plugin, @NotNull VoteSettingsService settings,
                            @NotNull VoteConfig config) {
        this.settings = settings;
        this.config = config;
        this.scheduler = PlatformScheduler.of(plugin);
    }

    /** Wires the back button to the vote menu. */
    public void setOverviewView(@NotNull VoteOverviewView view) {
        this.overviewView = view;
    }

    @Override protected @NotNull String title() { return KEY + "title"; }
    @Override protected int rows() { return 6; }
    @Override protected @NotNull InventoryHolder holder() { return holder; }

    @Override
    public void open(@NotNull Player viewer) {
        super.open(viewer);
        settings.load(viewer.getUniqueId()).thenRun(() -> scheduler.runAtEntity(viewer, () -> {
            if (isViewing(viewer)) {
                rerender(viewer);
            }
        }));
    }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {
        navBar(inv, viewer, overviewView == null ? null : KEY + "back-to-menu");
        VoteSettings current = settings.get(viewer.getUniqueId());
        List<VoteSettingOption> shown = shownOptions(viewer.getUniqueId());
        inv.setItem(SLOT_HEADER, header(viewer, current, shown));
        List<ItemStack> cards = new ArrayList<>(shown.size());
        for (VoteSettingOption option : shown) {
            cards.add(card(viewer, option, current));
        }
        int[] slots = VoteLayout.hub(cards.size());
        for (int i = 0; i < slots.length; i++) {
            inv.setItem(slots[i], cards.get(i));
        }
    }

    private @NotNull List<VoteSettingOption> shownOptions(@NotNull UUID viewer) {
        List<VoteSettingOption> shown = new ArrayList<>();
        for (VoteSettingOption option : VoteSettingOption.values()) {
            if (settings.availability(viewer, option) != Availability.HIDDEN) {
                shown.add(option);
            }
        }
        return shown;
    }

    // ── Header ────────────────────────────────────────────────────────

    private @NotNull ItemStack header(@NotNull Player viewer, @NotNull VoteSettings current,
                                      @NotNull List<VoteSettingOption> shown) {
        List<Component> rows = new ArrayList<>(shown.size());
        for (VoteSettingOption option : shown) {
            rows.add(VoteCards.rowOf(viewer, OPTION + option.id() + ".label", valueText(viewer, option, current)));
        }
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraphOf(viewer, KEY + "header.description"))
                .section(VoteCards.ic(viewer, KEY + "header.section"), rows);
        appendLoreExtra(lore, KEY + "header", viewer);
        return VoteCards.card(Material.COMPARATOR, VoteCards.ic(viewer, KEY + "header.name"), lore.build());
    }

    // ── Toggle cards ──────────────────────────────────────────────────

    private @NotNull ItemStack card(@NotNull Player viewer, @NotNull VoteSettingOption option,
                                    @NotNull VoteSettings current) {
        String base = OPTION + option.id();
        if (settings.availability(viewer.getUniqueId(), option) == Availability.UNAVAILABLE) {
            return VoteCards.card(LockedIcon.item(viewer),
                    VoteCards.ic(VoteCards.msg(base + NAME).with(PARAM_VALUE,
                            VoteCards.tone(viewer, "bad", VoteCards.text(viewer, KEY + "unavailable.value"))), viewer),
                    CardLore.create()
                            .block(VoteCards.paragraphOf(viewer, base + ".description"))
                            .block(VoteCards.paragraphOf(viewer, KEY + "unavailable.description"))
                            .build());
        }
        List<Component> values = new ArrayList<>(option.choices().size());
        int active = option.index(current);
        for (int i = 0; i < option.choices().size(); i++) {
            String dotKey = i == active ? VoteCards.COMMON + "filter.option-active" : VoteCards.COMMON + "filter.option";
            values.add(VoteCards.ic(VoteCards.msg(dotKey)
                    .with("name", VoteCards.text(viewer, VALUE + option.choices().get(i))), viewer));
        }
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraphOf(viewer, base + ".description"))
                .section(VoteCards.ic(viewer, KEY + "card.section"), values);
        List<Component> details = details(viewer, option);
        if (!details.isEmpty()) {
            lore.section(VoteCards.ic(viewer, KEY + "card.details"), details);
        }
        lore.block(List.of(VoteCards.ic(viewer, VoteCards.COMMON + "filter.action")));
        appendLoreExtra(lore, base, viewer);
        ItemStack card = VoteCards.card(ICONS.getOrDefault(option, Material.PAPER),
                VoteCards.ic(VoteCards.msg(base + NAME).with(PARAM_VALUE, valueText(viewer, option, current)), viewer),
                lore.build());
        tag(card, TAG_SETTING_PREFIX + option.name());
        return option.isActive(current) ? VoteCards.glint(card) : card;
    }

    private @NotNull List<Component> details(@NotNull Player viewer, @NotNull VoteSettingOption option) {
        VoteConfig.ReminderSettings timing = config.getReminderSettings();
        return switch (option) {
            case REMINDER -> List.of(VoteCards.rowOf(viewer, KEY + "label.at-most-every",
                    VoteCards.value(viewer, VoteFormat.duration(viewer, timing.intervalMinutes() * 60L))));
            case STREAK_WARNING -> List.of(VoteCards.rowOf(viewer, KEY + "label.warns-before",
                    VoteCards.value(viewer, VoteFormat.duration(viewer, timing.streakWarningHours() * 3600L))));
            case DISCORD -> List.of(VoteCards.rowOf(viewer, KEY + "label.at-most-every",
                    VoteCards.value(viewer, VoteFormat.duration(viewer, timing.discordIntervalMinutes() * 60L))));
            default -> List.of();
        };
    }

    private static @NotNull String valueText(@NotNull Player viewer, @NotNull VoteSettingOption option,
                                             @NotNull VoteSettings current) {
        String value = option.valueId(current);
        String tone;
        if (!option.isActive(current)) {
            tone = "muted";
        } else {
            tone = option.isSwitch() ? "ok" : "accent";
        }
        return VoteCards.tone(viewer, tone, VoteCards.text(viewer, VALUE + value));
    }

    private static @NotNull Map<VoteSettingOption, Material> icons() {
        Map<VoteSettingOption, Material> icons = new EnumMap<>(VoteSettingOption.class);
        icons.put(VoteSettingOption.REMINDER, Material.BELL);
        icons.put(VoteSettingOption.STREAK_WARNING, Material.CLOCK);
        icons.put(VoteSettingOption.BROADCASTS, Material.OAK_SIGN);
        icons.put(VoteSettingOption.PARTY, Material.CAKE);
        icons.put(VoteSettingOption.EFFECTS, Material.NOTE_BLOCK);
        icons.put(VoteSettingOption.DISCORD, Material.WRITABLE_BOOK);
        return icons;
    }

    // ── Clicks ────────────────────────────────────────────────────────

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        onClick(viewer, slot, clicked, ClickType.LEFT);
    }

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked, @NotNull ClickType type) {
        String id = tagOf(clicked);
        if (id == null) {
            return;
        }
        if (TAG_BACK.equals(id)) {
            if (overviewView != null) {
                overviewView.open(viewer);
            }
        } else if (id.startsWith(TAG_SETTING_PREFIX)) {
            VoteSettingOption option = VoteSettingOption.valueOf(id.substring(TAG_SETTING_PREFIX.length()));
            if (settings.availability(viewer.getUniqueId(), option) == Availability.AVAILABLE) {
                settings.cycle(viewer.getUniqueId(), option, !type.isRightClick());
                rerender(viewer);
            }
        }
    }

    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
