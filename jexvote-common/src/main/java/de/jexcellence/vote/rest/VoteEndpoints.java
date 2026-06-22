package de.jexcellence.vote.rest;

import com.sun.net.httpserver.HttpExchange;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.database.entity.VotePlayerEntity;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import de.jexcellence.vote.database.repository.VoteRecordRepository;
import de.jexcellence.vote.model.VoteSite;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only JSON endpoint handlers for the vote REST API.
 *
 * <p>Exposes the configured vote sites, and — per linked player UUID —
 * the per-site "next vote available" cooldown and aggregate vote stats.
 * Player UUIDs are supplied by the backend (from the linked Minecraft
 * account); names are returned, never looked up here.</p>
 */
final class VoteEndpoints {

    private static final String PLAYER_PREFIX = "/api/v1/vote/player/";

    private final VoteConfig voteConfig;
    private final VotePlayerRepository playerRepository;
    private final VoteRecordRepository recordRepository;

    VoteEndpoints(@NotNull VoteConfig voteConfig,
                  @NotNull VotePlayerRepository playerRepository,
                  @NotNull VoteRecordRepository recordRepository) {
        this.voteConfig = voteConfig;
        this.playerRepository = playerRepository;
        this.recordRepository = recordRepository;
    }

    /** {@code GET /api/v1/vote/sites} — the configured vote listing sites. */
    void handleSites(@NotNull HttpExchange exchange) throws IOException {
        List<SiteDto> sites = new ArrayList<>();
        for (VoteSite site : voteConfig.getVoteSites().values()) {
            sites.add(toSiteDto(site));
        }
        JsonResponse.send(exchange, 200, new SitesResponse(sites, sites.size(), Instant.now().toString()));
    }

    /**
     * {@code GET /api/v1/vote/player/{uuid}/cooldown} — per-site time
     * until the player can vote again.
     */
    void handlePlayerCooldown(@NotNull HttpExchange exchange) throws IOException {
        UUID uuid = parsePlayerUuid(exchange, "cooldown");
        if (uuid == null) {
            return; // error already written
        }
        long now = Instant.now().getEpochSecond();
        List<CooldownDto> rows = new ArrayList<>();
        for (VoteSite site : voteConfig.getVoteSites().values()) {
            long last = recordRepository
                    .findLatestByPlayerAndService(uuid, site.serviceName())
                    .map(record -> record.getVotedAt().getEpochSecond())
                    .orElse(0L);
            long secondsUntilNext = site.secondsUntilNextVote(last);
            Long nextVoteAt = secondsUntilNext > 0 ? now + secondsUntilNext : null;
            rows.add(new CooldownDto(
                    site.id(), site.displayName(), site.serviceName(), site.voteUrl(),
                    secondsUntilNext, secondsUntilNext == 0, nextVoteAt));
        }
        JsonResponse.send(exchange, 200,
                new CooldownResponse(uuid.toString(), rows, Instant.now().toString()));
    }

    /** {@code GET /api/v1/vote/player/{uuid}/stats} — aggregate vote stats. */
    void handlePlayerStats(@NotNull HttpExchange exchange) throws IOException {
        UUID uuid = parsePlayerUuid(exchange, "stats");
        if (uuid == null) {
            return;
        }
        Optional<VotePlayerEntity> player = playerRepository.findByUuid(uuid);
        StatsResponse body = player
                .map(p -> new StatsResponse(
                        uuid.toString(), p.getPlayerName(), p.getTotalVotes(), p.getMonthlyVotes(),
                        p.getCurrentStreak(), p.getHighestStreak(), p.getVotePoints(),
                        p.getLastVoteAt() != null ? p.getLastVoteAt().toString() : null,
                        Instant.now().toString()))
                .orElseGet(() -> new StatsResponse(
                        uuid.toString(), null, 0, 0, 0, 0, 0, null, Instant.now().toString()));
        JsonResponse.send(exchange, 200, body);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private @NotNull SiteDto toSiteDto(@NotNull VoteSite site) {
        boolean daily = site.dailyResetTime() != null;
        Long cooldownSeconds = site.cooldown() != null ? site.cooldown().getSeconds() : null;
        String dailyReset = daily ? site.dailyResetTime().toString() : null;
        return new SiteDto(
                site.id(), site.displayName(), site.serviceName(), site.voteUrl(),
                daily ? "daily-reset" : "rolling", cooldownSeconds, dailyReset,
                site.resetTimezone().getId(), site.pointsPerVote());
    }

    /**
     * Extracts and validates the {@code {uuid}} segment from a
     * {@code /api/v1/vote/player/{uuid}/<sub>} path. Writes a 400/404 and
     * returns null on a malformed path.
     */
    private UUID parsePlayerUuid(@NotNull HttpExchange exchange, @NotNull String expectedSub) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(PLAYER_PREFIX)) {
            JsonResponse.sendError(exchange, 404, "Not found");
            return null;
        }
        String[] parts = path.substring(PLAYER_PREFIX.length()).split("/");
        if (parts.length != 2 || !expectedSub.equals(parts[1])) {
            JsonResponse.sendError(exchange, 404, "Not found");
            return null;
        }
        try {
            return UUID.fromString(parts[0]);
        } catch (IllegalArgumentException ex) {
            JsonResponse.sendError(exchange, 400, "Invalid player UUID");
            return null;
        }
    }

    // ── Response shapes ───────────────────────────────────────────────────

    record SiteDto(String id, String displayName, String serviceName, String voteUrl,
                   String cooldownMode, Long cooldownSeconds, String dailyResetTime,
                   String timezone, int pointsPerVote) {}

    record SitesResponse(List<SiteDto> sites, int total, String generatedAt) {}

    record CooldownDto(String siteId, String displayName, String serviceName, String voteUrl,
                       long secondsUntilNext, boolean canVoteNow, Long nextVoteAtEpoch) {}

    record CooldownResponse(String playerUuid, List<CooldownDto> sites, String generatedAt) {}

    record StatsResponse(String playerUuid, String playerName, int totalVotes, int monthlyVotes,
                         int currentStreak, int highestStreak, int votePoints,
                         String lastVoteAt, String generatedAt) {}
}
