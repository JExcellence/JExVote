package de.jexcellence.vote.service;

import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.database.entity.VoteRecordEntity;
import de.jexcellence.vote.database.repository.VoteRecordRepository;
import de.jexcellence.vote.model.Vote;
import de.jexcellence.vote.model.VoteSite;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Offline-vote reconciliation. Votifier is real-time TCP: a vote cast while the
 * server is fully offline never reaches it and is lost, which also breaks the
 * player's streak because the day is never recorded. This service closes that gap
 * for sites that expose a status API — on join it asks each configured site
 * "did this player vote in the current window?" and, if yes and no vote from that
 * site is already recorded for the window, injects the vote through the normal
 * {@link VoteService#processVote(Vote)} pipeline so the reward, points and streak
 * day are credited exactly as a live vote would be.
 *
 * <p>Opt-in ({@code reconciliation.enabled}) and per-site: a site is only checked
 * when it has an {@code api-url}. Everything runs off the main thread via
 * {@link HttpClient#sendAsync}; dedupe is against the persisted {@link VoteRecordEntity}
 * history plus {@code processVote}'s own short-window guard, so a vote is never
 * double-credited.
 *
 * @author JExcellence
 */
public final class VoteReconciliationService implements Listener {

    /** Marker address recorded for reconciled votes (distinguishes them in logs/history). */
    private static final String RECONCILED_ADDRESS = "reconciled";

    private final VoteService voteService;
    private final VoteRecordRepository records;
    private final VoteConfig config;
    private final Logger logger;
    private final HttpClient http;

    /** playerUuid → epoch-ms of the last reconciliation sweep (per-player rate limit). */
    private final ConcurrentHashMap<UUID, Long> lastSweep = new ConcurrentHashMap<>();

    public VoteReconciliationService(@NotNull VoteService voteService,
                                     @NotNull VoteRecordRepository records,
                                     @NotNull VoteConfig config,
                                     @NotNull Logger logger) {
        this.voteService = voteService;
        this.records = records;
        this.config = config;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        VoteConfig.ReconciliationSettings settings = config.getReconciliationSettings();
        if (!settings.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldownMs = settings.playerCooldownMinutes() * 60_000L;
        Long last = lastSweep.get(uuid);
        if (last != null && now - last < cooldownMs) {
            return;
        }
        lastSweep.put(uuid, now);
        reconcile(uuid, player.getName(), settings.timeoutSeconds());
    }

    /**
     * Checks every API-enabled site for a vote the player cast that Votifier never
     * delivered, crediting any that are missing from the current window.
     */
    public void reconcile(@NotNull UUID uuid, @NotNull String username, int timeoutSeconds) {
        for (VoteSite site : config.getVoteSites().values()) {
            if (!site.hasApi()) {
                continue;
            }
            checkSite(uuid, username, site, timeoutSeconds);
        }
    }

    private void checkSite(@NotNull UUID uuid, @NotNull String username,
                           @NotNull VoteSite site, int timeoutSeconds) {
        String url = site.resolveApiUrl(username);
        if (url == null) {
            return;
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("User-Agent", "JExVote-Reconciliation")
                    .GET()
                    .build();
        } catch (IllegalArgumentException ex) {
            logger.log(Level.WARNING, () -> "[reconcile] invalid api-url for site "
                    + site.id() + ": " + ex.getMessage());
            return;
        }

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> handleResponse(uuid, username, site, response))
                .exceptionally(ex -> {
                    logger.log(Level.FINE, () -> "[reconcile] " + site.id()
                            + " status check failed for " + username + ": " + ex.getMessage());
                    return null;
                });
    }

    private void handleResponse(@NotNull UUID uuid, @NotNull String username,
                                @NotNull VoteSite site, @NotNull HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            return;
        }
        String body = response.body();
        if (body == null || !body.toLowerCase(Locale.ROOT)
                .contains(site.apiVotedContains().toLowerCase(Locale.ROOT))) {
            return; // the site reports the player has not voted in the current window
        }
        if (alreadyCreditedThisWindow(uuid, site)) {
            return;
        }
        voteService.processVote(new Vote(username, site.serviceName(), RECONCILED_ADDRESS, Instant.now()));
        logger.log(Level.INFO, () -> "[reconcile] credited offline vote for "
                + username + " on " + site.serviceName());
    }

    /**
     * Whether a vote from this site is already recorded within the site's current
     * cooldown/daily window — in which case the API "voted" flag refers to a vote we
     * already counted and must not be credited again. Runs on the HTTP callback
     * thread (never the main thread), so the synchronous repository read is safe.
     */
    private boolean alreadyCreditedThisWindow(@NotNull UUID uuid, @NotNull VoteSite site) {
        Optional<VoteRecordEntity> latest = records.findLatestByPlayerAndService(uuid, site.serviceName());
        if (latest.isEmpty()) {
            return false;
        }
        Instant votedAt = latest.get().getVotedAt();
        if (votedAt == null) {
            return false;
        }
        return site.secondsUntilNextVote(votedAt.getEpochSecond()) > 0L;
    }

    /** Drops the per-player rate-limit entry on quit to keep the map bounded. */
    public void forget(@NotNull UUID uuid) {
        lastSweep.remove(uuid);
    }
}
