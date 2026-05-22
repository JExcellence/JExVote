package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.jexplatform.view.RewardViewHelper;
import de.jexcellence.vote.service.StreakClaimService;
import de.jexcellence.vote.service.VoteRewardService;
import de.jexcellence.vote.service.VoteService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Streak milestone view showing current streak progress, reward-track
 * with actual reward icons, and claim-to-collect mechanics.
 * <p>
 * Two display modes:
 * <ul>
 *   <li><b>Track view</b> — milestone grid with reward icons and status</li>
 *   <li><b>Detail view</b> — expanded reward list for a single milestone with claim button</li>
 * </ul>
 */
public class VoteStreakView extends VoteBaseView {

    private static final String TAG_PREFIX_MILESTONE = "milestone:";
    private static final String TAG_PREFIX_CLAIM = "claim:";
    private static final String TAG_DETAIL_BACK = "detail-back";

    private static final int[] MILESTONE_GRID = {
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final Holder holder = new Holder();
    private final VoteService voteService;
    private final VoteRewardService rewardService;
    private final StreakClaimService claimService;
    private final PlatformScheduler scheduler;

    private VoteOverviewView overviewView;

    private final Map<UUID, ViewerState> stateByViewer =
            Collections.synchronizedMap(new WeakHashMap<>());

    public VoteStreakView(@NotNull JavaPlugin plugin,
                          @NotNull VoteService voteService,
                          @NotNull VoteRewardService rewardService,
                          @NotNull StreakClaimService claimService) {
        this.voteService = voteService;
        this.rewardService = rewardService;
        this.claimService = claimService;
        this.scheduler = PlatformScheduler.of(plugin);
    }

    public void setOverviewView(@NotNull VoteOverviewView view) { this.overviewView = view; }

    @Override protected @NotNull String title()           { return "vote_streak.title"; }
    @Override protected int rows()                         { return 6; }
    @Override protected @NotNull InventoryHolder holder()  { return holder; }

    // ── Lifecycle ──────────────────────────────────────────────────

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {
        ViewerState state = stateByViewer.computeIfAbsent(
                viewer.getUniqueId(), k -> new ViewerState());

        if (state.detailMilestone > 0) {
            renderDetail(inv, viewer, state);
        } else {
            renderTrack(inv, viewer, state);
        }
    }

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        String id = tagOf(clicked);
        if (id == null) return;

        ViewerState state = stateByViewer.computeIfAbsent(
                viewer.getUniqueId(), k -> new ViewerState());

        if ("back".equals(id)) {
            stateByViewer.remove(viewer.getUniqueId());
            if (overviewView != null) overviewView.open(viewer);
            return;
        }

        if (TAG_DETAIL_BACK.equals(id)) {
            state.detailMilestone = 0;
            open(viewer);
            return;
        }

        if (id.startsWith(TAG_PREFIX_MILESTONE)) {
            int day = parseDay(id, TAG_PREFIX_MILESTONE);
            if (day > 0) {
                state.detailMilestone = day;
                open(viewer);
            }
            return;
        }

        if (id.startsWith(TAG_PREFIX_CLAIM)) {
            int day = parseDay(id, TAG_PREFIX_CLAIM);
            if (day > 0) {
                handleClaim(viewer, day, state);
            }
        }
    }

    // ── Track view ─────────────────────────────────────────────────

    private void renderTrack(@NotNull Inventory inv, @NotNull Player viewer,
                              @NotNull ViewerState state) {
        // Row 0: Header
        glass(inv, Material.ORANGE_STAINED_GLASS_PANE, 0, 2, 6, 8);
        glass(inv, Material.YELLOW_STAINED_GLASS_PANE, 1, 7);
        inv.setItem(4, ItemBuilder.of(Material.MAGMA_CREAM)
                .name(ic("vote_streak.header.name", viewer))
                .glow(true)
                .lore(ics("vote_streak.header.lore", viewer))
                .build());

        // Row 1: Info placeholders (loading)
        glass(inv, Material.YELLOW_STAINED_GLASS_PANE, 9, 17);
        inv.setItem(11, ItemBuilder.of(Material.BLAZE_POWDER)
                .name(ic("vote_streak.your_streak.name", viewer))
                .lore(List.of(Component.empty(), ic("vote_streak.loading", viewer), Component.empty()))
                .build());
        inv.setItem(13, ItemBuilder.of(Material.BOOK)
                .name(ic("vote_streak.how_it_works.name", viewer))
                .lore(ics("vote_streak.how_it_works.lore", viewer))
                .build());
        inv.setItem(15, ItemBuilder.of(Material.EXPERIENCE_BOTTLE)
                .name(ic("vote_streak.progress.name", viewer))
                .lore(List.of(Component.empty(), ic("vote_streak.loading", viewer), Component.empty()))
                .build());

        // Row 2: Separator
        glass(inv, Material.ORANGE_STAINED_GLASS_PANE, 18, 22, 26);

        // Row 3-4: Milestone grid edges
        glass(inv, Material.ORANGE_STAINED_GLASS_PANE, 27, 35, 36, 44);

        // Row 5: Bottom bar + back
        glass(inv, Material.YELLOW_STAINED_GLASS_PANE, 46, 52);
        glass(inv, Material.ORANGE_STAINED_GLASS_PANE, 47, 53);
        inv.setItem(45, backButton());

        // Async: load stats + claimed days
        UUID uuid = viewer.getUniqueId();
        CompletableFuture<Set<Integer>> claimsFuture = claimService.getClaimedDays(uuid);

        voteService.getPlayerStats(uuid).thenCombine(claimsFuture, (stats, claimed) -> {
            state.claimedDays = claimed;
            scheduler.runAtEntity(viewer, () -> {
                Inventory top = viewer.getOpenInventory().getTopInventory();
                if (top.getHolder() != holder) return;

                int streak = stats.currentStreak();
                int highest = stats.highestStreak();
                state.highestStreak = highest;

                Map<Integer, List<AbstractReward>> milestones =
                        new TreeMap<>(rewardService.getStreakRewards());
                int nextMs = findNextMilestone(highest, milestones);
                boolean manualMode = rewardService.isManualStreakClaim();

                renderStreakInfo(top, viewer, streak, highest);
                renderRewardsSummary(top, viewer, streak, highest, milestones, claimed, manualMode);
                renderProgress(top, viewer, streak, nextMs);
                renderMilestoneGrid(top, viewer, streak, highest, nextMs, milestones, claimed, manualMode);
            });
            return null;
        });
    }

    // ── Detail view ────────────────────────────────────────────────

    private void renderDetail(@NotNull Inventory inv, @NotNull Player viewer,
                               @NotNull ViewerState state) {
        int day = state.detailMilestone;
        List<AbstractReward> rewards = rewardService.getStreakRewards().get(day);
        if (rewards == null) rewards = List.of();

        boolean manualMode = rewardService.isManualStreakClaim();
        boolean reached = state.highestStreak >= day;
        boolean claimed = state.claimedDays.contains(day);
        boolean claimable = manualMode && reached && !claimed;

        // Row 0: Header
        glass(inv, Material.ORANGE_STAINED_GLASS_PANE, 0, 1, 2, 6, 7, 8);
        String statusGradient = resolveStatusGradient(claimed, claimable, reached);
        String statusLabel = resolveStatusLabel(viewer, claimed, claimable, reached);

        inv.setItem(4, ItemBuilder.of(Material.MAGMA_CREAM)
                .name(msg("vote_streak.detail.header")
                        .with("day", day)
                        .with("status_gradient", statusGradient)
                        .with("status", statusLabel)
                        .itemComponent(viewer))
                .glow(claimable || claimed)
                .lore(List.of(
                        Component.empty(),
                        msg("vote_streak.detail.subtitle").with("day", day).itemComponent(viewer),
                        Component.empty()))
                .build());

        // Row 1: Separator
        glass(inv, Material.YELLOW_STAINED_GLASS_PANE, 9, 17);

        // Rows 2-3: Reward tiles
        List<AbstractReward> flatRewards = new ArrayList<>();
        for (AbstractReward reward : rewards) {
            flatRewards.addAll(RewardViewHelper.flatten(reward));
        }

        int[] rewardSlots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int i = 0; i < rewardSlots.length; i++) {
            if (i < flatRewards.size()) {
                inv.setItem(rewardSlots[i], buildRewardTile(flatRewards.get(i), viewer));
            }
        }

        // Row 4: Separator
        glass(inv, Material.ORANGE_STAINED_GLASS_PANE, 36, 44);

        // Row 5: Navigation
        glass(inv, Material.YELLOW_STAINED_GLASS_PANE, 46, 52);
        glass(inv, Material.ORANGE_STAINED_GLASS_PANE, 47, 53);

        ItemStack backBtn = ItemBuilder.of(Material.ARROW)
                .name(ic("vote_streak.detail.back_button", viewer))
                .build();
        tag(backBtn, TAG_DETAIL_BACK);
        inv.setItem(45, backBtn);

        if (claimable) {
            ItemStack claimBtn = ItemBuilder.of(Material.LIME_DYE)
                    .name(ic("vote_streak.detail.claim_button.name", viewer))
                    .glow(true)
                    .lore(ics("vote_streak.detail.claim_button.lore", viewer, day))
                    .build();
            tag(claimBtn, TAG_PREFIX_CLAIM + day);
            inv.setItem(49, claimBtn);
        } else if (claimed) {
            inv.setItem(49, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                    .name(ic("vote_streak.detail.already_claimed.name", viewer))
                    .lore(ics("vote_streak.detail.already_claimed.lore", viewer))
                    .build());
        } else {
            int remaining = day - state.highestStreak;
            String daysLabel = remaining == 1
                    ? msg("vote_streak.day_label").text(viewer)
                    : msg("vote_streak.days_label").text(viewer);
            inv.setItem(49, ItemBuilder.of(Material.RED_STAINED_GLASS_PANE)
                    .name(ic("vote_streak.detail.locked_status.name", viewer))
                    .lore(icsLocked("vote_streak.detail.locked_status.lore", viewer, day, remaining, daysLabel))
                    .build());
        }
    }

    // ── Claim handling ─────────────────────────────────────────────

    private void handleClaim(@NotNull Player viewer, int day, @NotNull ViewerState state) {
        claimService.claimMilestone(viewer, day).thenAccept(result -> {
            scheduler.runAtEntity(viewer, () -> {
                switch (result) {
                    case SUCCESS -> {
                        state.claimedDays = new HashSet<>(state.claimedDays);
                        state.claimedDays.add(day);
                        viewer.playSound(viewer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                        msg("vote_streak.claim.success").with("day", day).send(viewer);
                        open(viewer);
                    }
                    case ALREADY_CLAIMED -> msg("vote_streak.claim.already_claimed").send(viewer);
                    case NOT_REACHED -> msg("vote_streak.claim.not_reached").send(viewer);
                    default -> msg("vote_streak.claim.failed").send(viewer);
                }
            });
        });
    }

    // ── Track render helpers ───────────────────────────────────────

    private void renderStreakInfo(@NotNull Inventory inv, @NotNull Player viewer,
                                  int streak, int highest) {
        String currentLabel = streak == 1
                ? msg("vote_streak.day_label").text(viewer)
                : msg("vote_streak.days_label").text(viewer);
        String highestLabel = highest == 1
                ? msg("vote_streak.day_label").text(viewer)
                : msg("vote_streak.days_label").text(viewer);

        inv.setItem(11, ItemBuilder.of(Material.BLAZE_POWDER)
                .name(ic("vote_streak.your_streak.name", viewer))
                .glow(streak >= 7)
                .lore(ics("vote_streak.your_streak.lore", viewer,
                        streak, highest, currentLabel, highestLabel))
                .build());
    }

    private void renderRewardsSummary(@NotNull Inventory inv, @NotNull Player viewer,
                                       int streak, int highest,
                                       @NotNull Map<Integer, List<AbstractReward>> milestones,
                                       @NotNull Set<Integer> claimed, boolean manualMode) {
        int unlocked = countReached(highest, milestones);
        int claimedCount = (int) milestones.keySet().stream().filter(claimed::contains).count();
        int claimable = manualMode ? unlocked - claimedCount : 0;
        int locked = milestones.size() - unlocked;

        List<Component> summaryLore = new ArrayList<>();
        summaryLore.add(Component.empty());
        summaryLore.add(msg("vote_streak.milestones_label").with("count", milestones.size()).itemComponent(viewer));
        summaryLore.add(msg("vote_streak.unlocked_label").with("count", unlocked).itemComponent(viewer));
        if (manualMode && claimable > 0) {
            summaryLore.add(msg("vote_streak.claimable_label").with("count", claimable).itemComponent(viewer));
        }
        summaryLore.add(msg("vote_streak.remaining_label").with("count", locked).itemComponent(viewer));
        summaryLore.add(Component.empty());

        inv.setItem(13, ItemBuilder.of(Material.CHEST)
                .name(ic("vote_streak.rewards_summary.name", viewer))
                .lore(summaryLore)
                .build());
    }

    private void renderProgress(@NotNull Inventory inv, @NotNull Player viewer,
                                 int streak, int nextMs) {
        String bar = progressBar(streak, nextMs, 20);
        inv.setItem(15, ItemBuilder.of(Material.EXPERIENCE_BOTTLE)
                .name(ic("vote_streak.progress.name", viewer))
                .lore(ics("vote_streak.progress.lore", viewer, streak, nextMs, bar))
                .build());
    }

    private void renderMilestoneGrid(@NotNull Inventory inv, @NotNull Player viewer,
                                      int streak, int highest, int nextMs,
                                      @NotNull Map<Integer, List<AbstractReward>> milestones,
                                      @NotNull Set<Integer> claimed, boolean manualMode) {
        int idx = 0;
        for (var entry : milestones.entrySet()) {
            if (idx >= MILESTONE_GRID.length) break;
            ItemStack item = buildMilestoneItem(
                    entry.getKey(), entry.getValue(), viewer, streak, highest,
                    nextMs, claimed, manualMode);
            inv.setItem(MILESTONE_GRID[idx], item);
            idx++;
        }

        for (int i = idx; i < MILESTONE_GRID.length; i++) {
            inv.setItem(MILESTONE_GRID[i], ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                    .name(Component.empty()).build());
        }
    }

    private @NotNull ItemStack buildMilestoneItem(int day, @NotNull List<AbstractReward> rewards,
                                                    @NotNull Player viewer,
                                                    int streak, int highest, int nextMs,
                                                    @NotNull Set<Integer> claimed,
                                                    boolean manualMode) {
        boolean reached = highest >= day;
        boolean isClaimed = claimed.contains(day);
        boolean claimable = manualMode && reached && !isClaimed;
        boolean isNext = !reached && day == nextMs;

        Material mat = resolvePrimaryRewardMaterial(rewards, reached || isNext);
        String gradient = resolveGradient(isClaimed, claimable, reached, manualMode, isNext);

        // Build lore
        List<Component> itemLore = new ArrayList<>();
        itemLore.add(Component.empty());
        itemLore.add(resolveMilestoneStatus(viewer, isClaimed, claimable, reached, manualMode, isNext));

        if (!reached) {
            int remaining = day - highest;
            String daysLabel = remaining == 1
                    ? msg("vote_streak.day_label").text(viewer)
                    : msg("vote_streak.days_label").text(viewer);
            itemLore.add(msg("vote_streak.days_to_go")
                    .with("remaining", remaining)
                    .with("label", daysLabel)
                    .itemComponent(viewer));
        }

        itemLore.add(Component.empty());
        itemLore.add(ic("vote_streak.rewards_header", viewer));

        for (AbstractReward reward : rewards) {
            List<AbstractReward> flat = RewardViewHelper.flatten(reward);
            for (AbstractReward atomic : flat) {
                itemLore.add(lore("  <dark_gray>▸</dark_gray> " + RewardViewHelper.describe(atomic)));
            }
        }
        itemLore.add(Component.empty());

        String milestoneName = msg("vote_streak.milestone.name")
                .with("day", day)
                .text(viewer);
        Component nameComponent = MM.deserialize(gradient + milestoneName);

        ItemStack item = ItemBuilder.of(mat)
                .name(nameComponent)
                .glow(claimable || isNext)
                .lore(itemLore)
                .amount(Math.min(day, 64))
                .build();

        // Tag for click routing
        if (claimable || reached || isNext) {
            tag(item, TAG_PREFIX_MILESTONE + day);
        }

        return item;
    }

    // ── Reward tile for detail view ────────────────────────────────

    private @NotNull ItemStack buildRewardTile(@NotNull AbstractReward reward,
                                                @NotNull Player viewer) {
        RewardViewHelper.ViewEntry entry = RewardViewHelper.toViewEntry(reward);
        String description = RewardViewHelper.describe(reward);

        return ItemBuilder.of(entry.icon())
                .name(name(description))
                .lore(List.of(
                        Component.empty(),
                        msg("vote_streak.detail.reward_type")
                                .with("type", capitalize(reward.typeId()))
                                .itemComponent(viewer),
                        Component.empty()))
                .build();
    }

    // ── Status resolution helpers ──────────────────────────────────

    private @NotNull String resolveGradient(boolean claimed, boolean claimable,
                                             boolean reached, boolean manualMode,
                                             boolean isNext) {
        if (claimed) return "<gradient:#86efac:#16a34a>";
        if (claimable) return "<gradient:#fde047:#f59e0b>";
        if (reached && !manualMode) return "<gradient:#86efac:#16a34a>";
        if (isNext) return "<gradient:#fde047:#f59e0b>";
        return "<gradient:#fca5a5:#dc2626>";
    }

    private @NotNull Component resolveMilestoneStatus(@NotNull Player viewer,
                                                       boolean claimed, boolean claimable,
                                                       boolean reached, boolean manualMode,
                                                       boolean isNext) {
        if (claimed) return ic("vote_streak.milestone.claimed.status", viewer);
        if (claimable) return ic("vote_streak.milestone.claimable.status", viewer);
        if (reached && !manualMode) return ic("vote_streak.milestone.unlocked.status", viewer);
        if (isNext) return ic("vote_streak.milestone.next.status", viewer);
        return ic("vote_streak.milestone.locked.status", viewer);
    }

    private @NotNull String resolveStatusGradient(boolean claimed, boolean claimable,
                                                    boolean reached) {
        if (claimed) return "<gradient:#86efac:#16a34a>";
        if (claimable) return "<gradient:#fde047:#f59e0b>";
        if (reached) return "<gradient:#86efac:#16a34a>";
        return "<gradient:#fca5a5:#dc2626>";
    }

    private @NotNull String resolveStatusLabel(@NotNull Player viewer,
                                                boolean claimed, boolean claimable,
                                                boolean reached) {
        if (claimed) return msg("vote_streak.milestone.claimed.status").text(viewer);
        if (claimable) return msg("vote_streak.milestone.claimable.status").text(viewer);
        if (reached) return msg("vote_streak.milestone.unlocked.status").text(viewer);
        return msg("vote_streak.milestone.locked.status").text(viewer);
    }

    private static @NotNull Material resolvePrimaryRewardMaterial(
            @NotNull List<AbstractReward> rewards, boolean showRewardIcon) {
        if (!showRewardIcon || rewards.isEmpty()) {
            return rewards.isEmpty() ? Material.GRAY_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        }

        List<AbstractReward> flat = new ArrayList<>();
        for (AbstractReward reward : rewards) {
            flat.addAll(RewardViewHelper.flatten(reward));
        }

        if (flat.size() == 1) {
            return RewardViewHelper.toViewEntry(flat.getFirst()).icon();
        }
        return Material.CHEST;
    }

    // ── i18n lore helpers ──────────────────────────────────────────

    /**
     * Resolves multi-line lore with placeholders injected into the
     * {@code vote_streak.your_streak.lore} template.
     */
    private @NotNull List<Component> ics(@NotNull String key, @NotNull Player viewer,
                                          int current, int highest,
                                          @NotNull String currentLabel,
                                          @NotNull String highestLabel) {
        return msg(key)
                .with("current", current)
                .with("highest", highest)
                .with("current_label", currentLabel)
                .with("highest_label", highestLabel)
                .toComponents(viewer).stream()
                .map(c -> c.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
                .toList();
    }

    /**
     * Resolves multi-line lore for progress display.
     */
    private @NotNull List<Component> ics(@NotNull String key, @NotNull Player viewer,
                                          int current, int next, @NotNull String bar) {
        return msg(key)
                .with("current", current)
                .with("next", next)
                .with("bar", bar)
                .toComponents(viewer).stream()
                .map(c -> c.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
                .toList();
    }

    /**
     * Resolves multi-line lore for claim button with day placeholder.
     */
    private @NotNull List<Component> ics(@NotNull String key, @NotNull Player viewer,
                                          int day) {
        return msg(key)
                .with("day", day)
                .toComponents(viewer).stream()
                .map(c -> c.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
                .toList();
    }

    /**
     * Resolves multi-line lore for locked status with day, remaining, label.
     */
    private @NotNull List<Component> icsLocked(@NotNull String key, @NotNull Player viewer,
                                                int day, int remaining, @NotNull String label) {
        return msg(key)
                .with("day", day)
                .with("remaining", remaining)
                .with("label", label)
                .toComponents(viewer).stream()
                .map(c -> c.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
                .toList();
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static int findNextMilestone(int highest, Map<Integer, ?> milestones) {
        for (int day : milestones.keySet()) {
            if (day > highest) return day;
        }
        if (!milestones.isEmpty()) {
            return milestones.keySet().stream().mapToInt(Integer::intValue).max().orElse(highest);
        }
        return 7;
    }

    private static int countReached(int highest, Map<Integer, ?> milestones) {
        return (int) milestones.keySet().stream().filter(d -> highest >= d).count();
    }

    private static int parseDay(@NotNull String tag, @NotNull String prefix) {
        try {
            return Integer.parseInt(tag.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static @NotNull String capitalize(@Nullable String typeId) {
        if (typeId == null || typeId.isEmpty()) return "Reward";
        var sb = new StringBuilder();
        boolean cap = true;
        for (char c : typeId.toCharArray()) {
            if (c == '_' || c == '-') {
                sb.append(' ');
                cap = true;
            } else {
                sb.append(cap ? Character.toUpperCase(c) : c);
                cap = false;
            }
        }
        return sb.toString();
    }

    // ── Viewer state ───────────────────────────────────────────────

    private static final class ViewerState {
        int detailMilestone;
        int highestStreak;
        Set<Integer> claimedDays = Set.of();
    }

    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
