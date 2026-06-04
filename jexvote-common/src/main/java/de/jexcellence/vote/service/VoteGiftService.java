package de.jexcellence.vote.service;

import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.database.entity.VotePlayerEntity;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Vote Gifting: lets an active voter keep a friend's streak alive. A gift
 * advances ONLY the receiver's streak (+1 and resets their streak timeout) —
 * no reward items move, and the gifter keeps their own vote and rewards in
 * full. The gift is the streak-save itself, so it does not consume the
 * receiver's Streak Freezes.
 *
 * <p>Eligibility: the gifter must (optionally) have voted today and stay within
 * a per-day cap ({@code vote-gift.daily-limit}, raised by the highest
 * {@code jexvote.gift.daily.<n>} node). A receiver may be streak-advanced at
 * most once per day — repeat gifts (or a receiver who already voted today)
 * return {@link GiftResult#ALREADY_ADVANCED}, which also caps streak inflation.
 *
 * @author JExcellence
 */
public class VoteGiftService {

    /** Outcome category of a gift attempt. */
    public enum GiftResult {
        SUCCESS,
        DISABLED,
        GIFTER_NO_PROFILE,
        NOT_VOTED_TODAY,
        LIMIT_REACHED,
        SELF_GIFT,
        TARGET_NOT_FOUND,
        ALREADY_ADVANCED,
        NO_RANDOM_TARGET
    }

    /**
     * Detailed result of a gift attempt.
     *
     * @param result         the outcome category
     * @param targetName     the resolved receiver name (nullable on failure)
     * @param receiverStreak the receiver's streak after the gift (0 unless SUCCESS)
     * @param remainingToday gifts the gifter has left today (0 unless SUCCESS)
     */
    public record GiftOutcome(@NotNull GiftResult result,
                              @Nullable String targetName,
                              int receiverStreak,
                              int remainingToday) {

        static @NotNull GiftOutcome of(@NotNull GiftResult result) {
            return new GiftOutcome(result, null, 0, 0);
        }

        static @NotNull GiftOutcome of(@NotNull GiftResult result, @Nullable String targetName) {
            return new GiftOutcome(result, targetName, 0, 0);
        }
    }

    private static final String DAILY_PERMISSION_PREFIX = "jexvote.gift.daily.";

    private final VotePlayerRepository playerRepository;
    private final VoteConfig voteConfig;

    public VoteGiftService(@NotNull VotePlayerRepository playerRepository,
                           @NotNull VoteConfig voteConfig) {
        this.playerRepository = playerRepository;
        this.voteConfig = voteConfig;
    }

    /**
     * Returns the current gift settings, read live from the shared config so
     * {@code /jexvote reload} takes effect without extra plumbing.
     */
    public @NotNull VoteConfig.GiftSettings settings() {
        return voteConfig.getGiftSettings();
    }

    /**
     * Resolves the per-day gift cap for {@code player}: the config default,
     * raised by the highest {@code jexvote.gift.daily.<n>} node held.
     */
    public int resolveDailyLimit(@NotNull Player player) {
        int limit = settings().dailyLimit();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String perm = info.getPermission();
            if (perm == null || !perm.startsWith(DAILY_PERMISSION_PREFIX)) {
                continue;
            }
            String suffix = perm.substring(DAILY_PERMISSION_PREFIX.length());
            try {
                limit = Math.max(limit, Integer.parseInt(suffix.trim()));
            } catch (NumberFormatException ex) {
                // Non-numeric suffix — not a valid daily-override node, ignore
            }
        }
        return limit;
    }

    /**
     * Returns how many gifts the player has left today (resolved limit minus
     * gifts already sent during the current day).
     */
    public @NotNull CompletableFuture<Integer> remainingToday(@NotNull Player player) {
        int limit = resolveDailyLimit(player);
        String today = LocalDate.now(settings().timezone()).toString();
        return playerRepository.findByUuidAsync(player.getUniqueId()).thenApply(opt -> {
            int used = opt.filter(entity -> today.equals(entity.getGiftDay()))
                    .map(VotePlayerEntity::getGiftsToday)
                    .orElse(0);
            return Math.max(0, limit - used);
        });
    }

    /**
     * Gifts a streak advance to a specific player.
     */
    public @NotNull CompletableFuture<GiftOutcome> gift(@NotNull Player gifter, @NotNull OfflinePlayer target) {
        if (target.getUniqueId().equals(gifter.getUniqueId())) {
            return CompletableFuture.completedFuture(GiftOutcome.of(GiftResult.SELF_GIFT));
        }
        String targetName = target.getName() != null ? target.getName() : target.getUniqueId().toString();
        return gift(gifter, target.getUniqueId(), targetName);
    }

    /**
     * Gifts a streak advance to a random online player (never the gifter).
     */
    public @NotNull CompletableFuture<GiftOutcome> giftRandom(@NotNull Player gifter) {
        List<Player> candidates = Bukkit.getOnlinePlayers().stream()
                .filter(online -> !online.getUniqueId().equals(gifter.getUniqueId()))
                .map(online -> (Player) online)
                .toList();
        if (candidates.isEmpty()) {
            return CompletableFuture.completedFuture(GiftOutcome.of(GiftResult.NO_RANDOM_TARGET));
        }
        Player chosen = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        return gift(gifter, chosen.getUniqueId(), chosen.getName());
    }

    private @NotNull CompletableFuture<GiftOutcome> gift(@NotNull Player gifter,
                                                         @NotNull UUID targetUuid,
                                                         @NotNull String targetName) {
        VoteConfig.GiftSettings current = settings();
        if (!current.enabled()) {
            return CompletableFuture.completedFuture(GiftOutcome.of(GiftResult.DISABLED));
        }

        ZoneId zone = current.timezone();
        LocalDate today = LocalDate.now(zone);
        int dailyLimit = resolveDailyLimit(gifter);

        return playerRepository.findByUuidAsync(gifter.getUniqueId()).thenCompose(gifterOpt -> {
            if (gifterOpt.isEmpty()) {
                return CompletableFuture.completedFuture(GiftOutcome.of(GiftResult.GIFTER_NO_PROFILE));
            }
            VotePlayerEntity gifterEntity = gifterOpt.orElseThrow();

            if (current.requireVoteToday() && !votedOn(gifterEntity.getLastVoteAt(), today, zone)) {
                return CompletableFuture.completedFuture(GiftOutcome.of(GiftResult.NOT_VOTED_TODAY));
            }

            int usedToday = today.toString().equals(gifterEntity.getGiftDay()) ? gifterEntity.getGiftsToday() : 0;
            if (usedToday >= dailyLimit) {
                return CompletableFuture.completedFuture(GiftOutcome.of(GiftResult.LIMIT_REACHED));
            }

            return applyToReceiver(targetUuid, targetName, today, zone)
                    .thenApply(outcome -> {
                        if (outcome.result() != GiftResult.SUCCESS) {
                            return outcome;
                        }
                        gifterEntity.setGiftDay(today.toString());
                        gifterEntity.setGiftsToday(usedToday + 1);
                        playerRepository.update(gifterEntity);
                        int remaining = Math.max(0, dailyLimit - (usedToday + 1));
                        return new GiftOutcome(GiftResult.SUCCESS, outcome.targetName(),
                                outcome.receiverStreak(), remaining);
                    });
        });
    }

    private @NotNull CompletableFuture<GiftOutcome> applyToReceiver(@NotNull UUID targetUuid,
                                                                    @NotNull String targetName,
                                                                    @NotNull LocalDate today,
                                                                    @NotNull ZoneId zone) {
        return playerRepository.findByUuidAsync(targetUuid).thenApply(opt -> {
            Optional<VotePlayerEntity> maybe = opt;
            if (maybe.isEmpty()) {
                return GiftOutcome.of(GiftResult.TARGET_NOT_FOUND, targetName);
            }
            VotePlayerEntity receiver = maybe.orElseThrow();

            // A receiver advances at most once per day; this also blocks gifting
            // someone who already voted today and caps multi-gifter inflation.
            if (votedOn(receiver.getLastVoteAt(), today, zone)) {
                return GiftOutcome.of(GiftResult.ALREADY_ADVANCED, targetName);
            }

            int newStreak = receiver.getCurrentStreak() + 1;
            receiver.setCurrentStreak(newStreak);
            if (newStreak > receiver.getHighestStreak()) {
                receiver.setHighestStreak(newStreak);
            }
            // Reset the receiver's streak timeout so the gift genuinely saves it.
            receiver.setLastVoteAt(Instant.now());
            playerRepository.update(receiver);
            return new GiftOutcome(GiftResult.SUCCESS, targetName, newStreak, 0);
        });
    }

    private static boolean votedOn(@Nullable Instant lastVoteAt, @NotNull LocalDate day, @NotNull ZoneId zone) {
        return lastVoteAt != null && LocalDate.ofInstant(lastVoteAt, zone).isEqual(day);
    }
}
