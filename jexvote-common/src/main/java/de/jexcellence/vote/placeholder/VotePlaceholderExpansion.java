package de.jexcellence.vote.placeholder;

import de.jexcellence.vote.database.entity.VotePlayerEntity;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public class VotePlaceholderExpansion extends PlaceholderExpansion {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final VotePlayerRepository playerRepository;

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
        Optional<VotePlayerEntity> opt = playerRepository.findByUuid(player.getUniqueId());
        if (opt.isEmpty()) {
            return switch (params.toLowerCase()) {
                case "total", "monthly", "streak", "highest_streak", "points" -> "0";
                case "last_vote" -> "Never";
                case "player_name" -> player.getName();
                default -> null;
            };
        }

        VotePlayerEntity vp = opt.get();
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
}
