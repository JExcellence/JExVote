package de.jexcellence.vote.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.gui.style.LockedIcon;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.view.RewardViewHelper;
import de.jexcellence.vote.api.event.VoteRewardClaimedEvent;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.config.VoteFeatures;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.vote.service.MultiplierService;
import de.jexcellence.vote.service.SiteRewardLookup;
import de.jexcellence.vote.service.StreakFreezeService;
import de.jexcellence.vote.service.VotePartyService;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The vote menu ({@code /vote}), laid out in fixed bands so it reads top to bottom:
 * <pre>
 *  row 0  header (how voting works, sites ready, bonus / party right now)
 *  row 1  your votes | vote points | streak
 *  row 2-3  one card per vote site with its live status (centred, paged at 48 / 50)
 *  row 4  navigation: streaks, rewards, top voters, shop, vote party, settings
 *  row 5  close
 * </pre>
 * Cards for features that are off or not in the edition are left out and the rest re-centre. Stats and
 * cooldowns load asynchronously and the view redraws in place; a landed vote refreshes the open menu.
 *
 * @author JExcellence
 */
public class VoteOverviewView extends VoteBaseView {

    private static final String KEY = "vote_overview.";
    private static final String LABEL = VoteCards.COMMON + "label.";
    private static final String TAG_SITE_PREFIX = "site:";
    private static final String TAG_STREAKS = "streaks";
    private static final String TAG_REWARDS = "rewards";
    private static final String TAG_LEADERBOARD = "leaderboard";
    private static final String TAG_SHOP = "shop";
    private static final String TAG_PARTY = "party";
    private static final String TAG_SETTINGS = "settings";
    private static final String PARAM_VALUE = "value";
    private static final String PARAM_SITE = "site";
    private static final String TONE_ACCENT = "accent";
    private static final String DESCRIPTION = ".description";
    private static final int STATS_ROW = 1;
    private static final int SITES_FIRST_ROW = 2;
    private static final int SITES_ROWS = 2;
    private static final int SITES_PER_PAGE = SITES_ROWS * VoteLayout.BODY_COLUMNS;
    private static final int NAV_ROW = 4;

    private final Holder holder = new Holder();
    private final VoteService voteService;
    private final VoteFeatures features;
    private final VoteRewardConfig rewardConfig;
    private final StreakFreezeService freezeService;
    private final PlatformScheduler scheduler;
    private final Map<UUID, Integer> sitePage = new ConcurrentHashMap<>();
    private final Map<UUID, ViewerData> dataByViewer = new ConcurrentHashMap<>();

    private @Nullable MultiplierService multipliers;
    private @Nullable VotePartyService party;
    private @Nullable VoteLeaderboardView leaderboardView;
    private @Nullable VoteStreakView streakView;
    private @Nullable VoteRewardsView rewardsView;
    private @Nullable VoteShopView shopView;
    private @Nullable VotePartyView partyView;
    private @Nullable VoteSettingsView settingsView;

    /**
     * Async data the menu shows once loaded.
     *
     * @param stats     the player's vote snapshot
     * @param rank      all-time rank, or {@code -1}
     * @param cooldowns seconds until each service can be voted on again
     * @param freezes   owned Streak Freezes
     */
    private record ViewerData(@NotNull VoteSnapshot stats, int rank, @NotNull Map<String, Long> cooldowns,
                              int freezes) {
    }

    /** A navigation card: its icon, translation base and click tag. */
    private record NavCard(@NotNull Material icon, @NotNull String key, @NotNull String navTag) {
    }

    public VoteOverviewView(@NotNull JavaPlugin plugin,
                            @NotNull VoteService voteService,
                            @NotNull VoteFeatures features,
                            @NotNull VoteRewardConfig rewardConfig,
                            @NotNull StreakFreezeService freezeService) {
        this.voteService = voteService;
        this.features = features;
        this.rewardConfig = rewardConfig;
        this.freezeService = freezeService;
        this.scheduler = PlatformScheduler.of(plugin);
    }

    public void setMultipliers(@NotNull MultiplierService multipliers) { this.multipliers = multipliers; }

    public void setParty(@Nullable VotePartyService party) { this.party = party; }

    public void setLeaderboardView(@NotNull VoteLeaderboardView view) { this.leaderboardView = view; }

    public void setStreakView(@NotNull VoteStreakView view) { this.streakView = view; }

    public void setRewardsView(@NotNull VoteRewardsView view) { this.rewardsView = view; }

    public void setShopView(@NotNull VoteShopView view) { this.shopView = view; }

    public void setPartyView(@NotNull VotePartyView view) { this.partyView = view; }

    public void setSettingsView(@NotNull VoteSettingsView view) { this.settingsView = view; }

    @Override protected @NotNull String title() { return KEY + "title"; }
    @Override protected int rows() { return 6; }
    @Override protected @NotNull InventoryHolder holder() { return holder; }

    @Override
    protected void forget(@NotNull UUID viewer) {
        dataByViewer.remove(viewer);
        sitePage.remove(viewer);
    }

    @Override
    public void open(@NotNull Player viewer) {
        dataByViewer.remove(viewer.getUniqueId());
        super.open(viewer);
        load(viewer);
    }

    private void load(@NotNull Player viewer) {
        UUID uuid = viewer.getUniqueId();
        CompletableFuture<VoteSnapshot> stats = voteService.getPlayerStats(uuid);
        CompletableFuture<Integer> rank = voteService.getAllTimeRank(uuid);
        CompletableFuture<Map<String, Long>> cooldowns = voteService.voteCooldownsSeconds(uuid);
        CompletableFuture<Integer> freezes = features.freezes()
                ? freezeService.getOwned(uuid) : CompletableFuture.completedFuture(0);
        CompletableFuture.allOf(stats, rank, cooldowns, freezes).thenRun(() -> scheduler.runAtEntity(viewer, () -> {
            if (isViewing(viewer)) {
                dataByViewer.put(uuid, new ViewerData(stats.join(), rank.join(), cooldowns.join(), freezes.join()));
                rerender(viewer);
            }
        }));
    }

    @Override
    protected void render(@NotNull Inventory inv, @NotNull Player viewer) {
        ViewerData data = dataByViewer.get(viewer.getUniqueId());
        navBar(inv, viewer, null);
        inv.setItem(SLOT_HEADER, header(viewer, data));
        renderStats(inv, viewer, data);
        renderSites(inv, viewer, data);
        renderNavigation(inv, viewer);
    }

    // ── Header ────────────────────────────────────────────────────────

    private @NotNull ItemStack header(@NotNull Player viewer, @Nullable ViewerData data) {
        List<VoteSite> sites = new ArrayList<>(voteService.getVoteSites().values());
        List<Component> rows = new ArrayList<>();
        String ready;
        if (data == null) {
            ready = VoteCards.loading(viewer);
        } else {
            long readyCount = sites.stream().filter(site -> secondsFor(data, site) <= 0L).count();
            ready = VoteCards.ofTotal(viewer, readyCount, sites.size());
        }
        rows.add(VoteCards.rowOf(viewer, LABEL + "sites-ready", ready));
        MultiplierService bonus = multipliers;
        if (features.weekendBonus() && bonus != null && bonus.isActive()) {
            rows.add(VoteCards.rowOf(viewer, LABEL + "weekend-bonus", VoteCards.tone(viewer, "ok",
                    VoteFormat.multiplier(viewer, bonus.current()))));
        }
        VotePartyService activeParty = party;
        if (activeParty != null && features.party()) {
            rows.add(VoteCards.rowOf(viewer, LABEL + "vote-party",
                    VoteCards.ofTotal(viewer, activeParty.getCurrentVotes(), activeParty.getTargetVotes())));
        }
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraphOf(viewer, KEY + "intro.description"))
                .section(VoteCards.section(viewer, "now"), rows);
        appendLoreExtra(lore, KEY + "header", viewer);
        ItemStack card = VoteCards.card(Material.EMERALD, VoteCards.ic(VoteCards.msg(KEY + "intro.name")
                .with(PARAM_VALUE, ready), viewer), lore.build());
        return data != null && sites.stream().anyMatch(site -> secondsFor(data, site) <= 0L)
                ? VoteCards.glint(card) : card;
    }

    private static long secondsFor(@NotNull ViewerData data, @NotNull VoteSite site) {
        return data.cooldowns().getOrDefault(site.serviceName(), 0L);
    }

    // ── Stat tiles ────────────────────────────────────────────────────

    private void renderStats(@NotNull Inventory inv, @NotNull Player viewer, @Nullable ViewerData data) {
        List<ItemStack> tiles = new ArrayList<>();
        tiles.add(votesTile(viewer, data));
        tiles.add(pointsTile(viewer, data));
        if (features.streaks()) {
            tiles.add(streakTile(viewer, data));
        }
        int[] slots = VoteLayout.spacedRow(tiles.size(), STATS_ROW);
        for (int i = 0; i < slots.length; i++) {
            inv.setItem(slots[i], tiles.get(i));
        }
    }

    private @NotNull ItemStack votesTile(@NotNull Player viewer, @Nullable ViewerData data) {
        String base = KEY + "votes";
        CardLore lore = CardLore.create().block(VoteCards.paragraphOf(viewer, base + DESCRIPTION));
        String total;
        if (data == null) {
            total = VoteCards.loading(viewer);
            lore.section(VoteCards.section(viewer, "votes"),
                    List.of(VoteCards.rowOf(viewer, LABEL + "status", total)));
        } else {
            VoteSnapshot stats = data.stats();
            total = VoteCards.number(viewer, stats.totalVotes());
            List<Component> rows = new ArrayList<>();
            rows.add(VoteCards.rowOf(viewer, LABEL + "votes-total", total));
            rows.add(VoteCards.rowOf(viewer, LABEL + "votes-month", VoteCards.number(viewer, stats.monthlyVotes())));
            rows.add(VoteCards.rowOf(viewer, LABEL + "last-vote",
                    VoteCards.value(viewer, VoteFormat.ago(viewer, stats.lastVoteAt()))));
            if (features.leaderboard()) {
                String rank = data.rank() > 0
                        ? VoteCards.tone(viewer, TONE_ACCENT, VoteCards.msg(KEY + "rank")
                                .with(PARAM_VALUE, data.rank()).text(viewer))
                        : VoteCards.tone(viewer, "muted", VoteCards.text(viewer, KEY + "rank-none"));
                rows.add(VoteCards.rowOf(viewer, LABEL + "rank", rank));
            }
            lore.section(VoteCards.section(viewer, "votes"), rows);
        }
        appendLoreExtra(lore, base, viewer);
        return VoteCards.card(VoteCards.head(viewer.getUniqueId()), VoteCards.ic(VoteCards.msg(base + ".name")
                .with("player", viewer.getName()).with(PARAM_VALUE, total), viewer), lore.build());
    }

    private @NotNull ItemStack pointsTile(@NotNull Player viewer, @Nullable ViewerData data) {
        String base = KEY + "points";
        String points = data == null ? VoteCards.loading(viewer) : VoteCards.points(viewer, data.stats().votePoints());
        List<Component> uses = new ArrayList<>();
        if (features.shop()) {
            uses.add(VoteCards.rowOf(viewer, LABEL + "vote-shop", VoteCards.value(viewer,
                    VoteCards.msg(KEY + "points.shop-items")
                            .with(PARAM_VALUE, VoteFormat.number(viewer, rewardConfig.getVoteShopItems().size()))
                            .text(viewer))));
        }
        if (features.freezes()) {
            uses.add(VoteCards.rowOf(viewer, LABEL + "freezes",
                    VoteCards.points(viewer, freezeService.settings().costPoints())));
        }
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraphOf(viewer, base + DESCRIPTION))
                .section(VoteCards.section(viewer, "wallet"),
                        List.of(VoteCards.rowOf(viewer, LABEL + "vote-points", points)));
        if (!uses.isEmpty()) {
            lore.section(VoteCards.section(viewer, "spend-on"), uses);
        }
        boolean opensShop = features.shop() && shopView != null;
        if (opensShop) {
            lore.block(List.of(VoteCards.ic(viewer, KEY + "points.action")));
        }
        appendLoreExtra(lore, base, viewer);
        ItemStack card = VoteCards.card(Material.NETHER_STAR, VoteCards.ic(VoteCards.msg(base + ".name")
                .with(PARAM_VALUE, points), viewer), lore.build());
        if (opensShop) {
            tag(card, TAG_SHOP);
        }
        return card;
    }

    private @NotNull ItemStack streakTile(@NotNull Player viewer, @Nullable ViewerData data) {
        String base = KEY + "streak";
        CardLore lore = CardLore.create().block(VoteCards.paragraphOf(viewer, base + DESCRIPTION));
        String streak = data == null ? VoteCards.loading(viewer) : VoteCards.days(viewer, data.stats().currentStreak());
        if (data != null) {
            lore.section(VoteCards.section(viewer, "streak"), streakRows(viewer, data));
        }
        boolean opens = streakView != null;
        if (opens) {
            lore.block(List.of(VoteCards.ic(viewer, KEY + "streak.action")));
        }
        appendLoreExtra(lore, base, viewer);
        ItemStack card = VoteCards.card(Material.BLAZE_POWDER, VoteCards.ic(VoteCards.msg(base + ".name")
                .with(PARAM_VALUE, streak), viewer), lore.build());
        if (opens) {
            tag(card, TAG_STREAKS);
        }
        return card;
    }

    private @NotNull List<Component> streakRows(@NotNull Player viewer, @NotNull ViewerData data) {
        VoteSnapshot stats = data.stats();
        List<Component> rows = new ArrayList<>();
        rows.add(VoteCards.rowOf(viewer, LABEL + "streak", VoteCards.days(viewer, stats.currentStreak())));
        rows.add(VoteCards.rowOf(viewer, LABEL + "best-streak", VoteCards.days(viewer, stats.highestStreak())));
        int next = streakView == null ? 0 : streakView.nextMilestone(stats.highestStreak());
        if (next > 0) {
            rows.add(VoteCards.rowOf(viewer, LABEL + "next-milestone", VoteCards.value(viewer,
                    VoteCards.msg(VoteCards.COMMON + "value.day").with(PARAM_VALUE, next).text(viewer))));
            rows.add(VoteCards.bar(viewer, stats.currentStreak(), next));
        }
        if (features.freezes()) {
            rows.add(VoteCards.rowOf(viewer, LABEL + "freezes", VoteCards.number(viewer, data.freezes())));
        }
        return rows;
    }

    // ── Sites ─────────────────────────────────────────────────────────

    private void renderSites(@NotNull Inventory inv, @NotNull Player viewer, @Nullable ViewerData data) {
        List<VoteSite> sites = new ArrayList<>(voteService.getVoteSites().values());
        if (sites.isEmpty()) {
            inv.setItem(SLOT_CENTER, VoteCards.notice(viewer, LockedIcon.item(viewer), KEY + "no-sites"));
            return;
        }
        int pages = Math.max(1, (sites.size() + SITES_PER_PAGE - 1) / SITES_PER_PAGE);
        int page = VoteLayout.clampPage(sitePage.getOrDefault(viewer.getUniqueId(), 0), pages);
        sitePage.put(viewer.getUniqueId(), page);
        List<VoteSite> shown = sites.subList(page * SITES_PER_PAGE, Math.min(sites.size(), (page + 1) * SITES_PER_PAGE));
        int[] slots = VoteLayout.centredInBand(shown.size(), SITES_FIRST_ROW, SITES_ROWS);
        for (int i = 0; i < slots.length; i++) {
            VoteSite site = shown.get(i);
            Long seconds = data == null ? null : secondsFor(data, site);
            inv.setItem(slots[i], siteCard(viewer, site, seconds));
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
            icon = Material.CLOCK;
        }
        List<Component> rows = List.of(
                VoteCards.rowOf(viewer, "cooldown".equals(state) ? LABEL + "again-in" : LABEL + "status", status),
                VoteCards.rowOf(viewer, LABEL + "vote-points", VoteCards.tone(viewer, TONE_ACCENT,
                        VoteCards.msg(VoteCards.COMMON + "value.plus").with(PARAM_VALUE, site.pointsPerVote())
                                .text(viewer))));
        CardLore lore = CardLore.create()
                .block(VoteCards.paragraph(viewer, VoteCards.msg(KEY + "site.description")
                        .with(PARAM_SITE, site.displayName()).text(viewer)))
                .section(VoteCards.section(viewer, "this-vote"), rows);
        List<Component> extra = siteExtras(viewer, site);
        if (!extra.isEmpty()) {
            lore.section(VoteCards.section(viewer, "site-bonus"), extra);
        }
        lore.block(List.of(VoteCards.ic(viewer, site.voteUrl() != null ? KEY + "site.action" : KEY + "site.no-link")));
        appendLoreExtra(lore, KEY + "site", viewer);
        Component name = VoteCards.ic(VoteCards.msg(KEY + "site.name-" + state)
                .with(PARAM_SITE, site.displayName()), viewer);
        ItemStack card = VoteCards.card(icon, name, lore.build());
        if (site.voteUrl() != null) {
            tag(card, TAG_SITE_PREFIX + site.serviceName());
        }
        return "ready".equals(state) ? VoteCards.glint(card) : card;
    }

    private @NotNull List<Component> siteExtras(@NotNull Player viewer, @NotNull VoteSite site) {
        List<Component> lines = new ArrayList<>();
        for (AbstractReward reward : SiteRewardLookup.find(rewardConfig.getSiteRewards(), site.serviceName(), site.id())) {
            for (AbstractReward atomic : RewardViewHelper.flatten(reward)) {
                lines.add(VoteCards.reward(viewer, VoteRewardDescriber.describe(atomic, viewer)));
            }
        }
        return lines;
    }

    // ── Navigation ────────────────────────────────────────────────────

    private void renderNavigation(@NotNull Inventory inv, @NotNull Player viewer) {
        List<NavCard> cards = new ArrayList<>();
        if (features.streaks() && streakView != null) {
            cards.add(new NavCard(Material.BLAZE_POWDER, KEY + "nav.streaks", TAG_STREAKS));
        }
        if (rewardsView != null) {
            cards.add(new NavCard(Material.CHEST, KEY + "nav.rewards", TAG_REWARDS));
        }
        if (features.leaderboard() && leaderboardView != null) {
            cards.add(new NavCard(Material.GOLDEN_HELMET, KEY + "nav.leaderboard", TAG_LEADERBOARD));
        }
        if (features.shop() && shopView != null) {
            cards.add(new NavCard(Material.EMERALD, KEY + "nav.shop", TAG_SHOP));
        }
        if (features.party() && party != null && partyView != null) {
            cards.add(new NavCard(Material.CAKE, KEY + "nav.party", TAG_PARTY));
        }
        if (settingsView != null) {
            cards.add(new NavCard(Material.COMPARATOR, KEY + "nav.settings", TAG_SETTINGS));
        }
        int[] slots = VoteLayout.spacedRow(cards.size(), NAV_ROW);
        for (int i = 0; i < slots.length; i++) {
            NavCard nav = cards.get(i);
            CardLore lore = CardLore.create()
                    .block(VoteCards.paragraphOf(viewer, nav.key() + DESCRIPTION))
                    .block(List.of(VoteCards.ic(viewer, VoteCards.COMMON + "action.open")));
            appendLoreExtra(lore, nav.key(), viewer);
            ItemStack card = VoteCards.card(nav.icon(), VoteCards.ic(viewer, nav.key() + ".name"), lore.build());
            tag(card, nav.navTag());
            inv.setItem(slots[i], card);
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
            case TAG_LEADERBOARD -> leaderboardView;
            case TAG_STREAKS -> streakView;
            case TAG_REWARDS -> rewardsView;
            case TAG_SHOP -> shopView;
            case TAG_PARTY -> partyView;
            case TAG_SETTINGS -> settingsView;
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
