package de.jexcellence.vote.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Represents a configured vote listing site.
 *
 * <p>Cooldown can operate in two modes:</p>
 * <ul>
 *   <li><b>Rolling</b> - {@code cooldown} is set, {@code dailyResetTime}/{@code resetTimezone}
 *       are null. The player can vote again after the cooldown duration from their last vote.</li>
 *   <li><b>Daily reset</b> - {@code dailyResetTime} and {@code resetTimezone} are set.
 *       Voting resets at a fixed time of day (e.g. 00:00 UTC), matching how many vote sites work.</li>
 * </ul>
 */
public record VoteSite(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull String serviceName,
        @Nullable String voteUrl,
        @Nullable Duration cooldown,
        @Nullable LocalTime dailyResetTime,
        @NotNull ZoneId resetTimezone,
        int pointsPerVote,
        @Nullable String apiUrl,
        @Nullable String apiKey,
        @NotNull String apiVotedContains
) {

    /**
     * Whether this site has a status API configured for offline-vote reconciliation
     * (a vote cast while the server was down can be detected and credited on the
     * player's next join). Requires {@link #apiUrl()} to be set.
     */
    public boolean hasApi() {
        return apiUrl != null && !apiUrl.isBlank();
    }

    /**
     * Builds the resolved status-check URL for {@code username}, substituting the
     * {@code {username}} and {@code {key}} placeholders. Returns {@code null} when
     * no API is configured.
     */
    public @Nullable String resolveApiUrl(@NotNull String username) {
        if (!hasApi()) {
            return null;
        }
        return apiUrl
                .replace("{username}", username)
                .replace("{key}", apiKey == null ? "" : apiKey);
    }

    /**
     * Returns the number of seconds until the player can vote again,
     * based on their last vote timestamp. Returns 0 if ready now.
     *
     * @param lastVoteEpochSeconds epoch seconds of the player's last vote on this site
     * @return remaining cooldown in seconds, or 0 if ready
     */
    public long secondsUntilNextVote(long lastVoteEpochSeconds) {
        if (lastVoteEpochSeconds <= 0) return 0;

        ZonedDateTime now = ZonedDateTime.now(resetTimezone);

        if (dailyResetTime != null) {
            // Daily reset mode: find the last reset point
            ZonedDateTime lastReset = now.with(dailyResetTime);
            if (lastReset.isAfter(now)) {
                lastReset = lastReset.minusDays(1);
            }
            // Player can vote if their last vote was before the most recent reset
            ZonedDateTime lastVote = ZonedDateTime.ofInstant(
                    java.time.Instant.ofEpochSecond(lastVoteEpochSeconds), resetTimezone);
            if (lastVote.isBefore(lastReset)) {
                return 0; // Reset has passed since last vote
            }
            // Next reset is tomorrow
            ZonedDateTime nextReset = lastReset.plusDays(1);
            long remaining = Duration.between(now, nextReset).getSeconds();
            return Math.max(remaining, 0);
        }

        if (cooldown != null) {
            // Rolling cooldown mode
            long elapsedSeconds = now.toEpochSecond() - lastVoteEpochSeconds;
            long remaining = cooldown.getSeconds() - elapsedSeconds;
            return Math.max(remaining, 0);
        }

        return 0;
    }
}
