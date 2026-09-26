package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.api.event.VoteRewardClaimedEvent;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.vote.service.VoteService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The vote menu ({@code /vote}): the player's vote profile as the header, one card per vote site with its
 * live cooldown, and navigation cards to streaks, the leaderboard, rewards and the shop. Stats and cooldowns
 * load asynchronously and the view redraws in place; a landed vote refreshes the open menu.
 *
 * @author JExcellence
 */
public class VoteOverviewView extends VoteBaseView {

    private static final String KEY = "vote_overview.";
    private static final String LABEL = VoteCards.COMMON + "label.";
    private static final String TAG_SITE_PREFIX = "site:";
    private static final String PARAM_VALUE = "value";
    private static final String PARAM_SITE = "site";
    private static final int[] SITE_ROWS = {2, 3};
    private static final int SITES_PER_ROW = 7;
    private static final int SITES_PER_PAGE = SITE_ROWS.length * SITES_PER_ROW;
    private static final int NAV_ROW_CENTER = 40;

    private final Holder holder = new Holder();
    private final VoteService voteService;
    private final PlatformScheduler scheduler;
    private final VoteConfig voteConfig;
    private final Map<UUID, Integer> sitePage = new ConcurrentHashMap<>();
    private final Map<UUID, ViewerData> dataByViewer = new ConcurrentHashMap<>();

    private @Nullable VoteLeaderboardView leaderboardView;
    private @Nullable VoteStreakView streakView;
    private @Nullable VoteRewardsView rewardsView;
    private @Nullable VoteShopView shopView;

    /**
     * Async data the menu shows once loaded.
     *
     * @param stats     the player's vote snapshot
     * @param rank      all-time rank, or {@code -1}
     * @param cooldowns seconds until each service can be voted on again
     */
    private record ViewerData(@NotNull VoteSnapshot stats, int rank, @NotNull Map<String, Long> cooldowns) {
    }

    /** A navigation card: its icon, translation base and click tag. */
    private record NavCard(@NotNull Material icon, @NotNull String key, @NotNull String navTag) {
    }

    public VoteOverviewView(@NotNull JavaPlugin plugin,
                            @NotNull VoteService voteService,
                            @NotNull VoteConfig voteConfig) {
        this.voteService = voteService;
        this.voteConfig = voteConfig;
        this.scheduler = PlatformScheduler.of(plugin);
    }

    public void setLeaderboardView(@NotNull VoteLeaderboardView view) { this.leaderboardView = view; }

    public void setStreakView(@NotNull VoteStreakView view) { this.streakView = view; }

    public void setRewardsView(@NotNull VoteRewardsView view) { this.rewardsView = view; }

    public void setShopView(@NotNull VoteShopView view) { this.shopView = view; }

    @Override protected @NotNull String title() { return KEY + "title"; }
    @Override protected int rows() { return 6; }
    @Override protected @NotNull InventoryHolder holder() { return holder; }

    @Override
    public void open(@NotNull Player viewer) {
        dataByViewer.remove(viewer.getUniqueId());
        super.open(viewer);
        load(viewer);
    }

    private void load(@NotNull Player viewer) {
        UUID uuid = viewer.getUniqueId();
        var statsFuture = voteService.getPlayerStats(uuid);
        var rankFuture = voteService.getAllTimeRank(uuid);
        var cooldownFuture = voteService.voteCooldownsSeconds(uuid);
        statsFuture.thenCombine(rankFuture, (stats, rank) -> new ViewerData(stats, rank, Map.of()))
                .thenCombine(cooldownFuture, (data, cooldowns) -> new ViewerData(data.stats(), data.rank(), cooldowns))
                .thenAccept(data -> scheduler.runAtEntity(viewer, () -> {
                    if (isViewing(viewer)) {
                        dataByViewer.put(uuid, data);
                        rerender(viewer);
                    }
                }));
    }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {
        ViewerData data = dataByViewer.get(viewer.getUniqueId());
        navBar(inv, viewer, null);
        inv.setItem(SLOT_HEADER, header(viewer, data));
        renderSites(inv, viewer, data);
        renderNavigation(inv, viewer);
    }

    // ── Header ────────────────────────────────────────────────────────

    private @NotNull ItemStack header(@NotNull Player viewer, @Nullable ViewerData data) {
        CardLore lore = CardLore.create().block(VoteCards.paragraphOf(viewer, KEY + "header.description"));
        if (data == null) {
            lore.section(VoteCards.section(viewer, "votes"),
                    List.of(VoteCards.rowOf(viewer, LABEL + "status", VoteCards.loading(viewer))));
        } else {
            VoteSnapshot stats = data.stats();
            lore.section(VoteCards.section(viewer, "votes"), voteRows(viewer, data))
                    .section(VoteCards.section(viewer, "streak"), streakRows(viewer, stats))
                    .section(VoteCards.section(viewer, "wallet"), List.of(VoteCards.rowOf(viewer,
                            LABEL + "vote-points", VoteCards.points(viewer, stats.votePoints()))));
        }
        appendLoreExtra(lore, KEY + "header", viewer);
        ItemStack head = VoteCards.head(viewer.getUniqueId());
        return VoteCards.card(head, VoteCards.ic(VoteCards.msg(KEY + "header.name")
                .with("player", viewer.getName()), viewer), lore.build());
    }

    private @NotNull List<Component> voteRows(@NotNull Player viewer, @NotNull ViewerData data) {
        VoteSnapshot stats = data.stats();
        List<Component> rows = new ArrayList<>();
        rows.add(VoteCards.rowOf(viewer, LABEL + "votes-total", VoteCards.number(viewer, stats.totalVotes())));
        rows.add(VoteCards.rowOf(viewer, LABEL + "votes-month", VoteCards.number(viewer, stats.monthlyVotes())));
        rows.add(VoteCards.rowOf(viewer, LABEL + "last-vote",
                VoteCards.value(viewer, VoteFormat.ago(viewer, stats.lastVoteAt()))));
        if (data.rank() > 0) {
            rows.add(VoteCards.rowOf(viewer, LABEL + "rank", VoteCards.tone(viewer, "accent",
                    VoteCards.msg(KEY + "rank").with(PARAM_VALUE, data.rank()).text(viewer))));
        }
        return rows;
    }

    private @NotNull List<Component> streakRows(@NotNull Player viewer, @NotNull VoteSnapshot stats) {
        List<Component> rows = new ArrayList<>();
        rows.add(VoteCards.rowOf(viewer, LABEL + "streak", VoteCards.days(viewer, stats.currentStreak())));
        rows.add(VoteCards.rowOf(viewer, LABEL + "best-streak", VoteCards.days(viewer, stats.highestStreak())));
        int next = streakView == null ? 0 : streakView.nextMilestone(stats.highestStreak());
        if (next > 0) {
            rows.add(VoteCards.bar(viewer, stats.currentStreak(), next));
            rows.add(VoteCards.rowOf(viewer, LABEL + "next-milestone", VoteCards.value(viewer,
                    VoteCards.msg(VoteCards.COMMON + "value.day").with(PARAM_VALUE, next).text(viewer))));
        }
        return rows;
    }

    // ── Sites ─────────────────────────────────────────────────────────

    private void renderSites(@NotNull Inventory inv, @NotNull Player viewer, @Nullable ViewerData data) {
        List<VoteSite> sites = new ArrayList<>(voteService.getVoteSites().values());
        if (sites.isEmpty()) {
            inv.setItem(SLOT_CENTER, VoteCards.notice(viewer, Material.RED_DYE, KEY + "no-sites"));
            return;
        }
        int pages = Math.max(1, (sites.size() + SITES_PER_PAGE - 1) / SITES_PER_PAGE);
        int page = clampPage(sitePage.getOrDefault(viewer.getUniqueId(), 0), pages);
        sitePage.put(viewer.getUniqueId(), page);
        List<VoteSite> shown = sites.subList(page * SITES_PER_PAGE, Math.min(sites.size(), (page + 1) * SITES_PER_PAGE));
        for (int row = 0; row < SITE_ROWS.length; row++) {
            int from = row * SITES_PER_ROW;
            int count = Math.clamp(shown.size() - from, 0, SITES_PER_ROW);
            int start = SITE_ROWS[row] * 9 + 1 + (SITES_PER_ROW - count) / 2;
            for (int i = 0; i < count; i++) {
                VoteSite site = shown.get(from + i);
                Long seconds = data == null ? null : data.cooldowns().getOrDefault(site.serviceName(), 0L);
                inv.setItem(start + i, siteCard(viewer, site, seconds));
            }
        }
        pagination(inv, viewer, page, pages);
    }

    /**
     * @param secondsUntilNext {@code null} while loading, {@code 0} when the site can be voted on now
     */
    private @NotNull ItemStack siteCard(@NotNull Player viewer, @NotNull VoteSite site,
                                        @Nullable Long secondsUntilNext) {
        String state;
        String status;
        Material icon;
        if (secondsUntilNext == null) {
            state = "checking";
            status = VoteCards.loading(viewer);
            icon = Material.PAPER;
        } else if (secondsUntilNext <= 0L) {
            state = "ready";
            status = VoteCards.tone(viewer, "ok", VoteCards.text(viewer, KEY + "site.status-ready"));
            icon = Material.LIME_DYE;
        } else {
            state = "cooldown";
            status = VoteCards.tone(viewer, "warn", VoteFormat.duration(viewer, secondsUntilNext));
            icon = Material.RED_DYE;
        }
        List<Component> rows = List.of(
                VoteCards.rowOf(viewer, LABEL + "vote-points", VoteCards.tone(viewer, "accent",
                        VoteCards.msg(VoteCards.COMMON + "value.plus").with(PARAM_VALUE, site.pointsPerVote())
                                .text(viewer))),
                VoteCards.rowOf(viewer, "cooldown".equals(state) ? LABEL + "again-in" : LABEL + "status", status));
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraph(viewer, VoteCards.msg(KEY + "site.description")
                        .with(PARAM_SITE, site.displayName()).text(viewer)))
                .section(VoteCards.section(viewer, "this-vote"), rows);
        if (site.voteUrl() != null) {
            lore.block(List.of(VoteCards.ic(viewer, KEY + "site.action")));
        }
        appendLoreExtra(lore, KEY + "site", viewer);
        Component name = VoteCards.ic(VoteCards.msg(KEY + "site.name-" + state)
                .with(PARAM_SITE, site.displayName()), viewer);
        ItemStack card = VoteCards.card(icon, name, lore.build());
        tag(card, TAG_SITE_PREFIX + site.serviceName());
        return card;
    }

    // ── Navigation ────────────────────────────────────────────────────

    private void renderNavigation(@NotNull Inventory inv, @NotNull Player viewer) {
        List<NavCard> cards = new ArrayList<>();
        if (voteConfig.isFeatureStreaks() && streakView != null) {
            cards.add(new NavCard(Material.BLAZE_POWDER, KEY + "nav.streaks", "streaks"));
        }
        if (voteConfig.isFeatureLeaderboard() && leaderboardView != null) {
            cards.add(new NavCard(Material.GOLDEN_HELMET, KEY + "nav.leaderboard", "leaderboard"));
        }
        if (rewardsView != null) {
            cards.add(new NavCard(Material.CHEST, KEY + "nav.rewards", "rewards"));
        }
        if (voteConfig.isFeatureShop() && shopView != null) {
            cards.add(new NavCard(Material.EMERALD, KEY + "nav.shop", "shop"));
        }
        int start = NAV_ROW_CENTER - (cards.size() - 1);
        for (int i = 0; i < cards.size(); i++) {
            NavCard nav = cards.get(i);
            CardLore lore = CardLore.create()
                    .block(VoteCards.paragraphOf(viewer, nav.key() + ".description"))
                    .block(List.of(VoteCards.ic(viewer, VoteCards.COMMON + "action.open")));
            appendLoreExtra(lore, nav.key(), viewer);
            ItemStack card = VoteCards.card(nav.icon(), VoteCards.ic(viewer, nav.key() + ".name"), lore.build());
            tag(card, nav.navTag());
            inv.setItem(start + i * 2, card);
        }
    }

    // ── Clicks ────────────────────────────────────────────────────────

    @Override
    protected void onClick(@NotNull Player viewer, int slot, @NotNull ItemStack clicked) {
        String id = tagOf(clicked);
        if (id == null) {
            return;
        }
        if (id.startsWith(TAG_SITE_PREFIX)) {
            sendSiteLink(viewer, id.substring(TAG_SITE_PREFIX.length()));
        } else if (TAG_PAGE_PREV.equals(id) || TAG_PAGE_NEXT.equals(id)) {
            sitePage.merge(viewer.getUniqueId(), TAG_PAGE_PREV.equals(id) ? -1 : 1, Integer::sum);
            rerender(viewer);
        } else {
            openNavigation(viewer, id);
        }
    }

    private void openNavigation(@NotNull Player viewer, @NotNull String id) {
        VoteBaseView target = switch (id) {
            case "leaderboard" -> leaderboardView;
            case "streaks" -> streakView;
            case "rewards" -> rewardsView;
            case "shop" -> shopView;
            default -> null;
        };
        if (target != null) {
            target.open(viewer);
        }
    }

    private void sendSiteLink(@NotNull Player viewer, @NotNull String serviceName) {
        VoteSite site = voteService.findSiteByServiceName(serviceName);
        if (site == null || site.voteUrl() == null) {
            return;
        }
        viewer.closeInventory();
        msg("vote.site-link").prefix()
                .with(PARAM_SITE, site.displayName())
                .with("url", site.voteUrl())
                .send(viewer);
    }

    /**
     * Refreshes the open menu when the viewer's own vote lands, so the site they just voted on shows its
     * cooldown right away.
     *
     * @param event the claimed-reward event
     */
    @EventHandler
    public void onVoteRewardClaimed(@NotNull VoteRewardClaimedEvent event) {
        Player player = Bukkit.getPlayer(event.getPlayerUuid());
        if (player != null && isViewing(player)) {
            load(player);
        }
    }

    private static final class Holder implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
