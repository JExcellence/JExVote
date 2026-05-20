package de.jexcellence.vote.placeholder;

import de.jexcellence.vote.database.entity.VotePlayerEntity;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
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

    private final VotePlayerRepository playerRepository;
    private final Map<UUID, CachedPlayer> cache = new ConcurrentHashMap<>();

    public VotePlaceholderExpansion(@NotNull VotePlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
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
