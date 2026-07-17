package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.utility.item.HeadBuilder;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.vote.service.VoteService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main vote overview GUI. Shows player stats, vote sites, and navigation
 * to leaderboard / streak views.
 */
public class VoteOverviewView extends VoteBaseView {

    /*
     * Slot grid (6 × 9):
     *   0  1  2  3  4  5  6  7  8
     *   9 10 11 12 13 14 15 16 17
     *  18 19 20 21 22 23 24 25 26
     *  27 28 29 30 31 32 33 34 35
     *  36 37 38 39 40 41 42 43 44
     *  45 46 47 48 49 50 51 52 53
     */

    // ── Slot layout ──────────────────────────────────────────────────────
    // Row 0 (header)         — slot 4
    // Row 1 (stats)          — slots 11 / 13 / 15  (Identity / Points / Streak)
    // Row 3 (vote sites)     — slots 29-33 (5 centered), paging at 27/35
    // Row 5 (nav)            — 45 close, 46/48/50/52 buttons
    private static final int SLOT_HEADER       = 4;
    private static final int SLOT_IDENTITY     = 11;
    private static final int SLOT_POINTS       = 13;
    private static final int SLOT_STREAK       = 15;
    private static final int SLOT_PAGE_PREV    = 27;
    private static final int SLOT_PAGE_NEXT    = 35;
    private static final int[] SITE_SLOTS      = { 29, 30, 31, 32, 33 };
    private static final int SLOT_CLOSE        = 45;
    private static final int SLOT_NAV_LB       = 46;
    private static final int SLOT_NAV_STREAKS  = 48;
    private static final int SLOT_NAV_REWARDS  = 50;
    private static final int SLOT_NAV_SHOP     = 52;

    private static final String TAG_LEADERBOARD = "leaderboard";
    private static final String TAG_STREAKS     = "streaks";
    private static final String TAG_REWARDS     = "rewards";
    private static final String TAG_SHOP        = "shop";
    private static final String TAG_PAGE_PREV   = "page-prev";
    private static final String TAG_PAGE_NEXT   = "page-next";
    private static final String TAG_SITE_PREFIX = "site:";

    private static final int SITES_PER_PAGE = SITE_SLOTS.length;

    /**
     * Per-viewer page index of the vote-site row. Cleared on close to keep
     * the map small (only one row of state per active GUI).
     */
    private final Map<UUID, Integer> sitePage = new ConcurrentHashMap<>();

    private final Holder holder = new Holder();
    private final VoteService voteService;
    private final PlatformScheduler scheduler;
    private final VoteConfig voteConfig;

    private VoteLeaderboardView leaderboardView;
    private VoteStreakView streakView;
    private VoteRewardsView rewardsView;
    private VoteShopView shopView;

    public VoteOverviewView(@NotNull JavaPlugin plugin,
                            @NotNull VoteService voteService,
                            @NotNull VoteConfig voteConfig) {
        this.voteService = voteService;
        this.voteConfig = voteConfig;
        this.scheduler = PlatformScheduler.of(plugin);
    }

    /**
     * Sets the leaderboard view for navigation.
     *
     * @param view the leaderboard view
     */
    public void setLeaderboardView(@NotNull VoteLeaderboardView view) { this.leaderboardView = view; }

    /**
     * Sets the streak view for navigation.
     *
     * @param view the streak view
     */
    public void setStreakView(@NotNull VoteStreakView view) { this.streakView = view; }

    /**
     * Sets the rewards/economy view for navigation.
     *
     * @param view the rewards view
     */
    public void setRewardsView(@NotNull VoteRewardsView view) { this.rewardsView = view; }

    /**
     * Sets the vote-token shop view for navigation.
     *
     * @param view the shop view
     */
    public void setShopView(@NotNull VoteShopView view) { this.shopView = view; }

    @Override protected @NotNull String title()           { return "vote_overview.title"; }
    @Override protected int rows()                         { return 6; }
    @Override protected @NotNull InventoryHolder holder()  { return holder; }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {

        // ── 1-wide frame; content sits in the centered interior ──
        frame(inv, Material.LIME_STAINED_GLASS_PANE);

        // ── Header (slot 4) ────────────────────────────────────────
        inv.setItem(SLOT_HEADER, ItemBuilder.of(Material.EMERALD)
                .name(ic("vote_overview.header.name", viewer))
                .glow(true)
                .lore(ics("vote_overview.header.lore", viewer))
                .build());

        // ── Stat tiles (slots 11/13/15): placeholders, then async fill ──
        // Loading state: pass -1/null so each tile renders "…" sentinels.
        inv.setItem(SLOT_IDENTITY, identityTile(viewer, null, -1, -1));
        inv.setItem(SLOT_POINTS, pointsTile(viewer, -1));
        inv.setItem(SLOT_STREAK, streakTile(viewer, -1, -1, -1));

        voteService.getPlayerStats(viewer.getUniqueId()).thenAccept(stats ->
                scheduler.runAtEntity(viewer, () -> {
                    Inventory top = viewer.getOpenInventory().getTopInventory();
                    if (top.getHolder() != holder) {
                        return; // GUI was closed before async returned
                    }
                    int streak = stats.currentStreak();
                    int next = nextMilestone(streak);
                    top.setItem(SLOT_IDENTITY, identityTile(viewer, stats.lastVoteAt(),
                            stats.totalVotes(), stats.monthlyVotes()));
                    top.setItem(SLOT_POINTS, pointsTile(viewer, stats.votePoints()));
                    top.setItem(SLOT_STREAK, streakTile(viewer, streak, stats.highestStreak(), next));
                }));

        // ── Sites (paginated, slots 29-33) ─────────────────────────
        renderSites(inv, viewer);

        // ── Bottom nav (slots 45/46/48/50/52) ──────────────────────
        inv.setItem(SLOT_CLOSE, closeButton());
        if (voteConfig.isFeatureLeaderboard()) {
            inv.setItem(SLOT_NAV_LB, navTile(viewer, Material.GOLD_BLOCK, "vote_overview.nav.leaderboard", TAG_LEADERBOARD));
        }
        if (voteConfig.isFeatureStreaks()) {
            inv.setItem(SLOT_NAV_STREAKS, navTile(viewer, Material.MAGMA_CREAM, "vote_overview.nav.streaks", TAG_STREAKS));
        }
        inv.setItem(SLOT_NAV_REWARDS, navTile(viewer, Material.SPONGE, "vote_overview.nav.rewards", TAG_REWARDS));
        if (voteConfig.isFeatureShop()) {
            inv.setItem(SLOT_NAV_SHOP, navTile(viewer, Material.EMERALD_BLOCK, "vote_overview.nav.shop", TAG_SHOP));
        }
    }

    // ── Tile builders ────────────────────────────────────────────────

    /**
     * Identity tile: name + lifetime totals. Pass {@code total} and
     * {@code monthly} as -1 (with {@code lastVoteAt == null}) to render the
     * "…" loading placeholder before async stats arrive.
     */
    private @NotNull ItemStack identityTile(@NotNull Player viewer, @Nullable Instant lastVoteAt,
                                            int total, int monthly) {
        boolean loaded = total >= 0 && monthly >= 0;
        String totalText = loaded ? String.valueOf(total) : "…";
        String monthlyText = loaded ? String.valueOf(monthly) : "…";
        String lastVoted = loaded ? formatLastVoted(viewer, lastVoteAt) : "…";
        List<Component> lore = new ArrayList<>(plain(msg("vote_overview.identity.lore")
                .with("total", totalText)
                .with("monthly", monthlyText)
                .with("last_voted", lastVoted)
                .toComponents(viewer)));
        appendLoreExtra(lore, "vote_overview.identity", viewer);
        return HeadBuilder.fromPlayer(viewer)
                .name(plain(msg("vote_overview.identity.name")
                        .with("player", viewer.getName()).itemComponent(viewer)))
                .lore(lore)
                .build();
    }

    private @NotNull ItemStack pointsTile(@NotNull Player viewer, int points) {
        String pointsText = points < 0 ? "…" : String.valueOf(points);
        List<Component> lore = new ArrayList<>(plain(msg("vote_overview.points.lore")
                .with("points", pointsText).toComponents(viewer)));
        appendLoreExtra(lore, "vote_overview.points", viewer);
        return ItemBuilder.of(Material.NETHER_STAR)
                .name(ic("vote_overview.points.name", viewer))
                .glow(points > 0)
                .lore(lore)
                .build();
    }

    private @NotNull ItemStack streakTile(@NotNull Player viewer, int streak, int highest, int next) {
        boolean loaded = streak >= 0 && next > 0;
        String streakText = loaded ? String.valueOf(streak) : "…";
        String highestText = loaded ? String.valueOf(highest) : "…";
        String nextText = loaded ? String.valueOf(next) : "…";
        String bar = loaded ? progressBar(streak, next, 10) : "";
        List<Component> lore = new ArrayList<>(plain(msg("vote_overview.streak.lore")
                .with("streak", streakText)
                .with("highest", highestText)
                .with("next", nextText)
                .with("bar", bar)
                .toComponents(viewer)));
        appendLoreExtra(lore, "vote_overview.streak", viewer);
        return ItemBuilder.of(Material.BLAZE_POWDER)
                .name(ic("vote_overview.streak.name", viewer))
                .glow(loaded && streak >= 7)
                .lore(lore)
                .build();
    }

    private @NotNull ItemStack navTile(@NotNull Player viewer, @NotNull Material icon,
                                       @NotNull String keyBase, @NotNull String navTag) {
        List<Component> lore = new ArrayList<>(ics(keyBase + ".lore", viewer));
        appendLoreExtra(lore, keyBase, viewer);
        ItemStack tile = ItemBuilder.of(icon)
                .name(ic(keyBase + ".name", viewer))
                .glow(true)
                .lore(lore)
                .build();
        tag(tile, navTag);
        return tile;
    }

    // ── Site row ─────────────────────────────────────────────────────

    private void renderSites(@NotNull Inventory inv, @NotNull Player viewer) {
        List<VoteSite> sites = new ArrayList<>(voteService.getVoteSites().values());
        int totalPages = Math.max(1, (sites.size() + SITES_PER_PAGE - 1) / SITES_PER_PAGE);
        int page = clampPage(sitePage.getOrDefault(viewer.getUniqueId(), 0), totalPages);
        sitePage.put(viewer.getUniqueId(), page);

        int start = page * SITES_PER_PAGE;
        for (int i = 0; i < SITES_PER_PAGE; i++) {
            int siteIdx = start + i;
            int slot = SITE_SLOTS[i];
            if (siteIdx < sites.size()) {
                inv.setItem(slot, siteTile(viewer, sites.get(siteIdx)));
            } else {
                inv.setItem(slot, emptySiteTile(viewer));
            }
        }

        if (totalPages > 1) {
            inv.setItem(SLOT_PAGE_PREV, pageButton(viewer, "vote_overview.page-prev", TAG_PAGE_PREV, page, totalPages));
            inv.setItem(SLOT_PAGE_NEXT, pageButton(viewer, "vote_overview.page-next", TAG_PAGE_NEXT, page, totalPages));
        } else {
            inv.setItem(SLOT_PAGE_PREV, null);
            inv.setItem(SLOT_PAGE_NEXT, null);
        }
    }

    private @NotNull ItemStack siteTile(@NotNull Player viewer, @NotNull VoteSite site) {
        // Single material across every site so the gradient title does the
        // visual differentiation (was a cycling array of arbitrary materials).
        ItemStack tile = ItemBuilder.of(Material.PAPER)
                .name(plain(msg("vote_overview.site.name")
                        .with("site", site.displayName()).itemComponent(viewer)))
                .lore(plain(msg("vote_overview.site.lore")
                        .with("site", site.displayName())
                        .with("service", site.serviceName())
                        .with("points", String.valueOf(site.pointsPerVote()))
                        .toComponents(viewer)))
                .build();
        tag(tile, TAG_SITE_PREFIX + site.serviceName());
        return tile;
    }

    private @NotNull ItemStack emptySiteTile(@NotNull Player viewer) {
        return ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                .name(ic("vote_overview.site-empty.name", viewer))
                .lore(ics("vote_overview.site-empty.lore", viewer))
                .build();
    }

    private @NotNull ItemStack pageButton(@NotNull Player viewer, @NotNull String keyBase,
                                          @NotNull String navTag, int page, int totalPages) {
        ItemStack btn = ItemBuilder.of(Material.ARROW)
                .name(ic(keyBase + ".name", viewer))
                .lore(plain(msg(keyBase + ".lore")
                        .with("page", String.valueOf(page + 1))
                        .with("total", String.valueOf(totalPages))
                        .toComponents(viewer)))
                .build();
        tag(btn, navTag);
        return btn;
    }

    private static int clampPage(int page, int totalPages) {
        if (page < 0) {
            return 0;
        }
        return Math.min(page, totalPages - 1);
    }

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        String id = tagOf(clicked);
        if (id == null) {
            return;
        }
        if (handleNavigation(viewer, id)) {
            return;
        }
        if (handlePaging(viewer, id)) {
            return;
        }
        if (id.startsWith(TAG_SITE_PREFIX)) {
            handleSiteClick(viewer, id.substring(TAG_SITE_PREFIX.length()));
        }
    }

    private boolean handleNavigation(@NotNull Player viewer, @NotNull String id) {
        if (TAG_LEADERBOARD.equals(id) && leaderboardView != null) {
            leaderboardView.open(viewer);
            return true;
        }
        if (TAG_STREAKS.equals(id) && streakView != null) {
            streakView.open(viewer);
            return true;
        }
        if (TAG_REWARDS.equals(id) && rewardsView != null) {
            rewardsView.open(viewer);
            return true;
        }
        if (TAG_SHOP.equals(id) && shopView != null) {
            shopView.open(viewer);
            return true;
        }
        return false;
    }

    private boolean handlePaging(@NotNull Player viewer, @NotNull String id) {
        UUID uuid = viewer.getUniqueId();
        if (TAG_PAGE_PREV.equals(id)) {
            sitePage.put(uuid, Math.max(0, sitePage.getOrDefault(uuid, 0) - 1));
            renderSites(viewer.getOpenInventory().getTopInventory(), viewer);
            return true;
        }
        if (TAG_PAGE_NEXT.equals(id)) {
            sitePage.put(uuid, sitePage.getOrDefault(uuid, 0) + 1);
            renderSites(viewer.getOpenInventory().getTopInventory(), viewer);
            return true;
        }
        return false;
    }

    private void handleSiteClick(@NotNull Player viewer, @NotNull String serviceName) {
        VoteSite site = voteService.getVoteSites().values().stream()
                .filter(s -> serviceName.equals(s.serviceName()))
                .findFirst().orElse(null);
        if (site == null || site.voteUrl() == null) {
            return;
        }
        viewer.closeInventory();
        viewer.sendMessage(
                MM.deserialize("<gradient:#86EFAC:#22C55E>✔</gradient> <gray>Vote on</gray> <gradient:#A5F3FC:#06B6D4>"
                                + site.displayName() + "</gradient><gray>:</gray> ")
                        .append(Component.text(site.voteUrl(), NamedTextColor.AQUA)
                                .clickEvent(ClickEvent.openUrl(site.voteUrl()))
                                .hoverEvent(HoverEvent.showText(
                                        Component.text("Click to open in browser", NamedTextColor.YELLOW)))));
    }

    /**
     * Formats {@code lastVoteAt} as a short "X days ago" / "today" string,
     * resolving from the {@code vote_overview.points-never} i18n key when the
     * player has never voted. Resolution is server-default-locale (placeholders
     * carry no localized data themselves).
     */
    private @NotNull String formatLastVoted(@NotNull Player viewer, @Nullable Instant lastVoteAt) {
        if (lastVoteAt == null) {
            return MM.serialize(ic("vote_overview.points-never", viewer));
        }
        Duration since = Duration.between(lastVoteAt, Instant.now());
        long days = since.toDays();
        if (days >= 1) {
            return days + " day(s) ago";
        }
        long hours = since.toHours();
        if (hours >= 1) {
            return hours + "h ago";
        }
        long minutes = Math.max(1, since.toMinutes());
        return minutes + "m ago";
    }

    // ── Helpers ─────────────────────────────────────────────────

    /**
     * Returns the next milestone day based on the current streak.
     *
     * @param streak the current vote streak
     * @return the next milestone day
     */
    private static int nextMilestone(int streak) {
        for (int m : new int[]{7, 14, 30, 60, 90, 120, 180, 365}) {
            if (streak < m) return m;
        }
        return 365;
    }

    /**
     * Inventory holder for the overview view.
     */
    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
