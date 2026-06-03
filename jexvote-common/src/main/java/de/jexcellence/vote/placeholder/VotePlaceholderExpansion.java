package de.jexcellence.vote.placeholder;

import de.jexcellence.vote.database.entity.VotePlayerEntity;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import de.jexcellence.vote.service.RewardStatsService;
import de.jexcellence.vote.service.VotePartyService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PlaceholderAPI expansion that caches vote data to avoid
 * synchronous database queries on the main thread.
 */
public class VotePlaceholderExpansion extends PlaceholderExpansion {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private static final String REWARD_COUNT_PREFIX = "reward_count_";

    private final VotePlayerRepository playerRepository;
    private final @Nullable VotePartyService votePartyService;
    private final @Nullable RewardStatsService rewardStatsService;
    private final Map<UUID, CachedPlayer> cache = new ConcurrentHashMap<>();

    public VotePlaceholderExpansion(@NotNull VotePlayerRepository playerRepository,
                                    @Nullable VotePartyService votePartyService,
                                    @Nullable RewardStatsService rewardStatsService) {
        this.playerRepository = playerRepository;
        this.votePartyService = votePartyService;
        this.rewardStatsService = rewardStatsService;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "jexvote";
    }

    @Override
    public @NotNull String getAuthor() {
        return "JExcellence";
    }

    @Override
    public @NotNull String getVersion() {
        return "3.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@NotNull OfflinePlayer player, @NotNull String params) {
        String global = resolveGlobal(params.toLowerCase());
        if (global != null) {
            return global;
        }

        UUID uuid = player.getUniqueId();
        CachedPlayer cached = cache.get(uuid);

        if (cached == null || cached.isExpired()) {
            // Trigger async refresh; return cached/default value for now
            refreshAsync(uuid);
            if (cached == null) {
                return defaultValue(params, player.getName());
            }
        }

        VotePlayerEntity vp = cached.entity;
        if (vp == null) {
            return defaultValue(params, player.getName());
        }

        return switch (params.toLowerCase()) {
            case "total" -> String.valueOf(vp.getTotalVotes());
            case "monthly" -> String.valueOf(vp.getMonthlyVotes());
            case "streak" -> String.valueOf(vp.getCurrentStreak());
            case "highest_streak" -> String.valueOf(vp.getHighestStreak());
            case "points" -> String.valueOf(vp.getVotePoints());
            case "last_vote" -> vp.getLastVoteAt() != null
                    ? DATE_FORMAT.format(vp.getLastVoteAt()) : "Never";
            case "player_name" -> vp.getPlayerName() != null ? vp.getPlayerName() : player.getName();
            default -> null;
        };
    }

    /**
     * Resolves placeholders that do not depend on a specific player (vote party
     * progress and reward grant counts). Returns {@code null} when {@code params}
     * is not a global placeholder, so the caller falls through to player data.
     */
    private @Nullable String resolveGlobal(@NotNull String params) {
        if (params.startsWith(REWARD_COUNT_PREFIX)) {
            String key = params.substring(REWARD_COUNT_PREFIX.length());
            long count = rewardStatsService != null ? rewardStatsService.getCount(key) : 0L;
            return String.valueOf(count);
        }

        return switch (params) {
            case "party_current" -> String.valueOf(votePartyService != null ? votePartyService.getCurrentVotes() : 0);
            case "party_target" -> String.valueOf(votePartyService != null ? votePartyService.getTargetVotes() : 0);
            case "party_remaining" -> String.valueOf(votePartyService != null ? votePartyService.getRemainingVotes() : 0);
            default -> null;
        };
    }

    private void refreshAsync(@NotNull UUID uuid) {
        playerRepository.findByUuidAsync(uuid).thenAccept(opt ->
                cache.put(uuid, new CachedPlayer(opt.orElse(null))));
    }

    private static @Nullable String defaultValue(@NotNull String params, @Nullable String name) {
        return switch (params.toLowerCase()) {
            case "total", "monthly", "streak", "highest_streak", "points" -> "0";
            case "last_vote" -> "Never";
            case "player_name" -> name;
            default -> null;
        };
    }

    private record CachedPlayer(@Nullable VotePlayerEntity entity, Instant fetchedAt) {
        CachedPlayer(@Nullable VotePlayerEntity entity) {
            this(entity, Instant.now());
        }
        boolean isExpired() {
            return Duration.between(fetchedAt, Instant.now()).compareTo(CACHE_TTL) > 0;
        }
    }
}
