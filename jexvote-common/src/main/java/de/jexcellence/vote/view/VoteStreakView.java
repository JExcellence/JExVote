package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.gui.component.FilterHopperButton;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.view.RewardViewHelper;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.service.StreakClaimService;
import de.jexcellence.vote.service.VoteRewardService;
import de.jexcellence.vote.service.VoteService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streak milestones. The track shows the streak header, the shared filter and one card per milestone; a
 * ready milestone is claimed with a left-click, any milestone opens its detail page with a right-click (or a
 * click when there is nothing to claim). The detail page lists every reward of that day with the claim card.
 *
 * @author JExcellence
 */
public class VoteStreakView extends VoteBaseView {

    private static final String KEY = "vote_streak.";
    private static final String LABEL = VoteCards.COMMON + "label.";
    private static final String TAG_MILESTONE = "milestone:";
    private static final String TAG_CLAIM = "claim:";
    private static final String TAG_DETAIL_BACK = "detail-back";
    private static final String PARAM_DAY = "day";
    private static final String PARAM_VALUE = "value";
    private static final String[] FILTERS_MANUAL = {"all", "claimable", "reached", "locked"};
    private static final String[] FILTERS_AUTO = {"all", "reached", "locked"};
    private static final int SLOT_CLAIM = 49;
    private static final int MAX_STACK = 64;

    /** Where a milestone stands for one player. */
    private enum MilestoneState { CLAIMED, CLAIMABLE, REACHED, NEXT, LOCKED }

    private final Holder holder = new Holder();
    private final VoteService voteService;
    private final VoteRewardService rewardService;
    private final StreakClaimService claimService;
    private final PlatformScheduler scheduler;
    private final FilterHopperButton manualFilter = new FilterHopperButton("vote-streak-state", FILTERS_MANUAL.length);
    private final FilterHopperButton autoFilter = new FilterHopperButton("vote-streak-state-auto", FILTERS_AUTO.length);
    private final Map<UUID, ViewerState> stateByViewer = new ConcurrentHashMap<>();

    private @Nullable VoteOverviewView overviewView;

    public VoteStreakView(@NotNull JavaPlugin plugin,
                          @NotNull VoteService voteService,
                          @NotNull VoteRewardService rewardService,
                          @NotNull StreakClaimService claimService) {
        this.voteService = voteService;
        this.rewardService = rewardService;
        this.claimService = claimService;
        this.scheduler = PlatformScheduler.of(plugin);
    }

    public void setOverviewView(@NotNull VoteOverviewView view) {
        this.overviewView = view;
    }

    @Override protected @NotNull String title() { return KEY + "title"; }
    @Override protected int rows() { return 6; }
    @Override protected @NotNull InventoryHolder holder() { return holder; }

    @Override
    protected void forget(@NotNull UUID viewer) {
        stateByViewer.remove(viewer);
    }

    /**
     * The next milestone above the best streak, used by the vote menu header.
     *
     * @param highest the player's best streak
     * @return the milestone day, or {@code 0} when every milestone is reached
     */
    public int nextMilestone(int highest) {
        for (int day : milestones().keySet()) {
            if (day > highest) {
                return day;
            }
        }
        return 0;
    }

    private @NotNull Map<Integer, List<AbstractReward>> milestones() {
        return new TreeMap<>(rewardService.getStreakRewards());
    }

    /** Opens the milestone track with freshly loaded streak data. */
    @Override
    public void open(@NotNull Player viewer) {
        ViewerState state = new ViewerState();
        stateByViewer.put(viewer.getUniqueId(), state);
        super.open(viewer);
        load(viewer, state);
    }

    private void load(@NotNull Player viewer, @NotNull ViewerState state) {
        UUID uuid = viewer.getUniqueId();
        voteService.getPlayerStats(uuid).thenCombine(claimService.getClaimedDays(uuid), (stats, claimed) -> {
            scheduler.runAtEntity(viewer, () -> {
                state.current = stats.currentStreak();
                state.highest = stats.highestStreak();
                state.claimedDays = new HashSet<>(claimed);
                state.loaded = true;
                if (isViewing(viewer)) {
                    rerender(viewer);
                }
            });
            return null;
        });
    }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {
        ViewerState state = stateByViewer.computeIfAbsent(viewer.getUniqueId(), uuid -> new ViewerState());
        if (state.detailDay > 0 && state.loaded) {
            renderDetail(inv, viewer, state);
        } else {
            renderTrack(inv, viewer, state);
        }
    }

    // ── Track ─────────────────────────────────────────────────────────

    private void renderTrack(@NotNull Inventory inv, @NotNull Player viewer, @NotNull ViewerState state) {
        navBar(inv, viewer, overviewView == null ? null : KEY + "back");
        Map<Integer, List<AbstractReward>> milestones = milestones();
        inv.setItem(SLOT_HEADER, header(viewer, state, milestones));
        if (!state.loaded) {
            inv.setItem(SLOT_CENTER, VoteCards.notice(viewer, Material.CLOCK, KEY + "pending"));
            return;
        }
        if (milestones.isEmpty()) {
            inv.setItem(SLOT_CENTER, VoteCards.notice(viewer, Material.RED_DYE, KEY + "empty"));
            return;
        }
        String[] filters = filters();
        int filter = stateFilter().index(viewer.getUniqueId());
        inv.setItem(SLOT_FILTER, filterButton(viewer, filters, filter));
        List<Integer> days = milestones.keySet().stream()
                .filter(day -> matches(filters[filter], stateOf(day, state)))
                .toList();
        if (days.isEmpty()) {
            inv.setItem(SLOT_CENTER, VoteCards.notice(viewer, Material.PAPER, KEY + "none-in-filter"));
            return;
        }
        state.page = renderPage(inv, viewer, days, state.page,
                (index, day) -> milestoneCard(viewer, day, milestones.get(day), state, true));
    }

    private @NotNull String[] filters() {
        return rewardService.isManualStreakClaim() ? FILTERS_MANUAL : FILTERS_AUTO;
    }

    private @NotNull FilterHopperButton stateFilter() {
        return rewardService.isManualStreakClaim() ? manualFilter : autoFilter;
    }

    private static boolean matches(@NotNull String filter, @NotNull MilestoneState state) {
        return switch (filter) {
            case "claimable" -> state == MilestoneState.CLAIMABLE;
            case "reached" -> state == MilestoneState.CLAIMED || state == MilestoneState.REACHED;
            case "locked" -> state == MilestoneState.NEXT || state == MilestoneState.LOCKED;
            default -> true;
        };
    }

    private @NotNull ItemStack filterButton(@NotNull Player viewer, @NotNull String[] filters, int active) {
        List<String> labels = new ArrayList<>(filters.length);
        for (String filter : filters) {
            labels.add(VoteCards.text(viewer, KEY + "filter." + filter));
        }
        ItemStack button = VoteCards.filter(viewer, labels, active);
        tag(button, FilterHopperButton.TAG);
        return button;
    }

    private @NotNull ItemStack header(@NotNull Player viewer, @NotNull ViewerState state,
                                      @NotNull Map<Integer, List<AbstractReward>> milestones) {
        CardLore lore = CardLore.create().block(VoteCards.paragraphOf(viewer, KEY + "header.description"));
        String streakValue = state.loaded ? VoteFormat.days(viewer, state.current) : VoteCards.text(viewer,
                VoteCards.COMMON + "value.loading");
        if (state.loaded) {
            lore.section(VoteCards.section(viewer, "now"), nowRows(viewer, state))
                    .section(VoteCards.section(viewer, "milestones"), milestoneRows(viewer, state, milestones));
        }
        appendLoreExtra(lore, KEY + "header", viewer);
        return VoteCards.card(Material.BLAZE_POWDER, VoteCards.ic(VoteCards.msg(KEY + "header.name")
                .with(PARAM_VALUE, streakValue), viewer), lore.build());
    }

    private @NotNull List<Component> nowRows(@NotNull Player viewer, @NotNull ViewerState state) {
        List<Component> rows = new ArrayList<>();
        rows.add(VoteCards.rowOf(viewer, LABEL + "streak", VoteCards.days(viewer, state.current)));
        rows.add(VoteCards.rowOf(viewer, LABEL + "best-streak", VoteCards.days(viewer, state.highest)));
        int next = nextMilestone(state.highest);
        if (next > 0) {
            rows.add(VoteCards.bar(viewer, state.current, next));
            rows.add(VoteCards.rowOf(viewer, LABEL + "next-milestone", VoteCards.value(viewer,
                    VoteCards.msg(VoteCards.COMMON + "value.day").with(PARAM_VALUE, next).text(viewer))));
        }
        return rows;
    }

    private @NotNull List<Component> milestoneRows(@NotNull Player viewer, @NotNull ViewerState state,
                                                   @NotNull Map<Integer, List<AbstractReward>> milestones) {
        long reached = milestones.keySet().stream().filter(day -> state.highest >= day).count();
        List<Component> rows = new ArrayList<>();
        rows.add(VoteCards.rowOf(viewer, LABEL + "reached", VoteCards.ofTotal(viewer, reached, milestones.size())));
        if (rewardService.isManualStreakClaim()) {
            long claimable = milestones.keySet().stream()
                    .filter(day -> stateOf(day, state) == MilestoneState.CLAIMABLE).count();
            rows.add(VoteCards.rowOf(viewer, LABEL + "ready-to-claim", claimable > 0
                    ? VoteCards.tone(viewer, "accent", VoteFormat.number(viewer, claimable))
                    : VoteCards.number(viewer, 0)));
        }
        return rows;
    }

    // ── Milestone card ────────────────────────────────────────────────

    private @NotNull MilestoneState stateOf(int day, @NotNull ViewerState state) {
        boolean reached = state.highest >= day;
        if (state.claimedDays.contains(day)) {
            return MilestoneState.CLAIMED;
        }
        if (reached) {
            return rewardService.isManualStreakClaim() ? MilestoneState.CLAIMABLE : MilestoneState.REACHED;
        }
        return day == nextMilestone(state.highest) ? MilestoneState.NEXT : MilestoneState.LOCKED;
    }

    private @NotNull ItemStack milestoneCard(@NotNull Player viewer, int day, @NotNull List<AbstractReward> rewards,
                                             @NotNull ViewerState state, boolean onTrack) {
        MilestoneState milestone = stateOf(day, state);
        List<Component> rewardRows = new ArrayList<>();
        for (AbstractReward atomic : flatten(rewards)) {
            rewardRows.add(VoteCards.reward(viewer, VoteRewardDescriber.describe(atomic, viewer)));
        }
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraph(viewer, VoteCards.msg(KEY + "milestone.description")
                        .with(PARAM_DAY, day).text(viewer)))
                .section(VoteCards.section(viewer, "rewards"), rewardRows);
        if (milestone == MilestoneState.NEXT || milestone == MilestoneState.LOCKED) {
            List<Component> progress = new ArrayList<>();
            if (milestone == MilestoneState.NEXT) {
                progress.add(VoteCards.bar(viewer, state.current, day));
            }
            progress.add(VoteCards.rowOf(viewer, LABEL + "streak", VoteCards.ofTotal(viewer, state.current, day)));
            lore.section(VoteCards.section(viewer, "progress"), progress);
        }
        lore.block(stateLines(viewer, day, milestone, state, onTrack));
        Component name = VoteCards.ic(VoteCards.msg(KEY + "milestone.name-" + stateKey(milestone))
                .with(PARAM_DAY, day), viewer);
        Material icon = milestone == MilestoneState.LOCKED ? Material.RED_DYE : primaryIcon(rewards);
        ItemStack card = VoteCards.card(icon, name, lore.build());
        card.setAmount(Math.clamp(day, 1, MAX_STACK));
        if (milestone == MilestoneState.CLAIMABLE) {
            VoteCards.glint(card);
        }
        if (onTrack) {
            tag(card, TAG_MILESTONE + day);
        }
        return card;
    }

    private @NotNull List<Component> stateLines(@NotNull Player viewer, int day, @NotNull MilestoneState milestone,
                                                @NotNull ViewerState state, boolean onTrack) {
        List<Component> lines = new ArrayList<>();
        String remaining = VoteFormat.days(viewer, Math.max(0, day - state.current));
        lines.add(VoteCards.ic(VoteCards.msg(KEY + "milestone.status-" + stateKey(milestone))
                .with(PARAM_VALUE, remaining), viewer));
        if (onTrack) {
            String actionKey = milestone == MilestoneState.CLAIMABLE ? "milestone.action-claim" : "milestone.action-details";
            lines.add(VoteCards.ic(viewer, KEY + actionKey));
        }
        return lines;
    }

    private static @NotNull String stateKey(@NotNull MilestoneState state) {
        return state.name().toLowerCase(Locale.ROOT);
    }

    private static @NotNull List<AbstractReward> flatten(@NotNull List<AbstractReward> rewards) {
        return rewards.stream().flatMap(reward -> RewardViewHelper.flatten(reward).stream()).toList();
    }

    private static @NotNull Material primaryIcon(@NotNull List<AbstractReward> rewards) {
        List<AbstractReward> flat = flatten(rewards);
        if (flat.size() == 1) {
            return VoteRewardDescriber.icon(flat.getFirst());
        }
        return Material.CHEST;
    }

    // ── Detail ────────────────────────────────────────────────────────

    private void renderDetail(@NotNull Inventory inv, @NotNull Player viewer, @NotNull ViewerState state) {
        int day = state.detailDay;
        List<AbstractReward> rewards = milestones().getOrDefault(day, List.of());
        ItemStack back = backButton(viewer, KEY + "detail.back");
        tag(back, TAG_DETAIL_BACK);
        inv.setItem(SLOT_BACK, back);
        inv.setItem((rows() - 1) * 9, closeButton(viewer));
        inv.setItem(SLOT_HEADER, milestoneCard(viewer, day, rewards, state, false));
        List<AbstractReward> flat = flatten(rewards);
        List<ItemStack> cards = new ArrayList<>();
        for (AbstractReward reward : flat.subList(0, Math.min(flat.size(), VoteLayout.PAGE_SIZE))) {
            cards.add(rewardCard(viewer, day, reward));
        }
        placeCentred(inv, cards);
        inv.setItem(SLOT_CLAIM, claimCard(viewer, day, stateOf(day, state), state));
    }

    private @NotNull ItemStack rewardCard(@NotNull Player viewer, int day, @NotNull AbstractReward reward) {
        return VoteCards.card(VoteRewardDescriber.icon(reward),
                VoteCards.ic(VoteCards.msg(KEY + "detail.reward-name")
                        .with("reward", VoteRewardDescriber.describe(reward, viewer)), viewer),
                CardLore.create().block(VoteCards.paragraph(viewer, VoteCards.msg(KEY + "detail.reward-description")
                        .with(PARAM_DAY, day).text(viewer))).build());
    }

    private @NotNull ItemStack claimCard(@NotNull Player viewer, int day, @NotNull MilestoneState milestone,
                                         @NotNull ViewerState state) {
        String base = KEY + "detail.claim-" + stateKey(milestone);
        CardLore lore = CardLore.create().block(VoteCards.paragraph(viewer, VoteCards.msg(base + ".description")
                .with(PARAM_DAY, day).text(viewer)));
        Material icon;
        switch (milestone) {
            case CLAIMABLE -> {
                icon = Material.LIME_DYE;
                lore.block(List.of(VoteCards.ic(viewer, KEY + "detail.claim-action")));
            }
            case CLAIMED, REACHED -> icon = Material.PAPER;
            default -> {
                icon = Material.RED_DYE;
                lore.section(VoteCards.section(viewer, "progress"), List.of(
                        VoteCards.rowOf(viewer, LABEL + "streak", VoteCards.ofTotal(viewer, state.current, day))));
            }
        }
        ItemStack card = VoteCards.card(icon, VoteCards.ic(VoteCards.msg(base + ".name").with(PARAM_DAY, day), viewer),
                lore.build());
        if (milestone == MilestoneState.CLAIMABLE) {
            tag(VoteCards.glint(card), TAG_CLAIM + day);
        }
        return card;
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
        ViewerState state = stateByViewer.computeIfAbsent(viewer.getUniqueId(), uuid -> new ViewerState());
        if (TAG_BACK.equals(id)) {
            stateByViewer.remove(viewer.getUniqueId());
            if (overviewView != null) {
                overviewView.open(viewer);
            }
        } else if (id.startsWith(TAG_MILESTONE)) {
            onMilestoneClick(viewer, parseDay(id, TAG_MILESTONE), state, type);
        } else if (id.startsWith(TAG_CLAIM)) {
            claim(viewer, parseDay(id, TAG_CLAIM), state);
        } else {
            onNavigationClick(viewer, id, state, type);
        }
    }

    private void onNavigationClick(@NotNull Player viewer, @NotNull String id, @NotNull ViewerState state,
                                   @NotNull ClickType type) {
        switch (id) {
            case TAG_DETAIL_BACK -> state.detailDay = 0;
            case TAG_PAGE_PREV -> state.page = Math.max(0, state.page - 1);
            case TAG_PAGE_NEXT -> state.page++;
            case FilterHopperButton.TAG -> {
                stateFilter().cycle(viewer.getUniqueId(), !type.isRightClick());
                state.page = 0;
            }
            default -> {
                return;
            }
        }
        rerender(viewer);
    }

    private void onMilestoneClick(@NotNull Player viewer, int day, @NotNull ViewerState state,
                                  @NotNull ClickType type) {
        if (day <= 0) {
            return;
        }
        if (!type.isRightClick() && stateOf(day, state) == MilestoneState.CLAIMABLE) {
            claim(viewer, day, state);
            return;
        }
        state.detailDay = day;
        rerender(viewer);
    }

    private void claim(@NotNull Player viewer, int day, @NotNull ViewerState state) {
        if (day <= 0) {
            return;
        }
        claimService.claimMilestone(viewer, day).thenAccept(result -> scheduler.runAtEntity(viewer, () -> {
            switch (result) {
                case SUCCESS -> {
                    state.claimedDays.add(day);
                    viewer.playSound(viewer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                    msg(KEY + "claim.success").with(PARAM_DAY, day).prefix().send(viewer);
                }
                case ALREADY_CLAIMED -> {
                    state.claimedDays.add(day);
                    msg(KEY + "claim.already_claimed").prefix().send(viewer);
                }
                case NOT_REACHED -> msg(KEY + "claim.not_reached").prefix().send(viewer);
                case BUSY -> {
                    // Intentionally silent: the first click is still being processed.
                }
                default -> msg(KEY + "claim.failed").prefix().send(viewer);
            }
            if (isViewing(viewer)) {
                rerender(viewer);
            }
        }));
    }

    private static int parseDay(@NotNull String tag, @NotNull String prefix) {
        try {
            return Integer.parseInt(tag.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Per-viewer state of the streak view. */
    private static final class ViewerState {
        private int detailDay;
        private int page;
        private int current;
        private int highest;
        private boolean loaded;
        private Set<Integer> claimedDays = new HashSet<>();
    }

    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
