package de.jexcellence.vote.service;

import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.database.entity.VotePlayerEntity;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Purchases and inventory logic for Streak Freezes (Duolingo-style streak
 * protection). The actual consumption happens automatically inside
 * {@link VoteService} when a vote arrives after the streak would have broken;
 * this service only handles buying freezes and resolving per-player caps.
 *
 * <p>The owned-freeze cap is {@code default-max} from config, raised by the
 * highest {@code jexvote.freeze.max.<n>} permission node a player holds.
 *
 * @author JExcellence
 */
public class StreakFreezeService {

    /** Outcome of a {@link #purchase(Player)} attempt. */
    public enum PurchaseResult {
        SUCCESS,
        DISABLED,
        AT_MAX,
        NOT_ENOUGH_POINTS,
        NO_PROFILE
    }

    private static final String MAX_PERMISSION_PREFIX = "jexvote.freeze.max.";

    private final VotePlayerRepository playerRepository;
    private final VoteConfig voteConfig;

    public StreakFreezeService(@NotNull VotePlayerRepository playerRepository,
                               @NotNull VoteConfig voteConfig) {
        this.playerRepository = playerRepository;
        this.voteConfig = voteConfig;
    }

    /**
     * Returns the current freeze settings, read live from the shared config so
     * {@code /jexvote reload} takes effect without extra plumbing.
     */
    public @NotNull VoteConfig.FreezeSettings settings() {
        return voteConfig.getFreezeSettings();
    }

    /**
     * Resolves the maximum number of freezes {@code player} may own: the config
     * default, raised by the highest {@code jexvote.freeze.max.<n>} node held.
     */
    public int resolveMax(@NotNull Player player) {
        int max = settings().defaultMax();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String perm = info.getPermission();
            if (perm == null || !perm.startsWith(MAX_PERMISSION_PREFIX)) {
                continue;
            }
            String suffix = perm.substring(MAX_PERMISSION_PREFIX.length());
            try {
                max = Math.max(max, Integer.parseInt(suffix.trim()));
            } catch (NumberFormatException ex) {
                // Non-numeric suffix — not a valid max-override node, ignore
            }
        }
        return max;
    }

    /**
     * Returns the number of Streak Freezes the player currently owns
     * (0 if they have no vote profile yet).
     */
    public @NotNull CompletableFuture<Integer> getOwned(@NotNull UUID uuid) {
        return playerRepository.findByUuidAsync(uuid)
                .thenApply(opt -> opt.map(VotePlayerEntity::getStreakFreezes).orElse(0));
    }

    /**
     * Returns the player's current vote-point balance (0 if no profile yet).
     */
    public @NotNull CompletableFuture<Integer> getPoints(@NotNull UUID uuid) {
        return playerRepository.findByUuidAsync(uuid)
                .thenApply(opt -> opt.map(VotePlayerEntity::getVotePoints).orElse(0));
    }

    /**
     * Attempts to buy one Streak Freeze for the player, charging vote points.
     */
    public @NotNull CompletableFuture<PurchaseResult> purchase(@NotNull Player player) {
        VoteConfig.FreezeSettings current = settings();
        if (!current.enabled()) {
            return CompletableFuture.completedFuture(PurchaseResult.DISABLED);
        }

        int max = resolveMax(player);
        UUID uuid = player.getUniqueId();

        return playerRepository.findByUuidAsync(uuid).thenApply(opt -> {
            Optional<VotePlayerEntity> maybe = opt;
            if (maybe.isEmpty()) {
                return PurchaseResult.NO_PROFILE;
            }
            VotePlayerEntity entity = maybe.orElseThrow();
            if (entity.getStreakFreezes() >= max) {
                return PurchaseResult.AT_MAX;
            }
            if (entity.getVotePoints() < current.costPoints()) {
                return PurchaseResult.NOT_ENOUGH_POINTS;
            }
            entity.setVotePoints(entity.getVotePoints() - current.costPoints());
            entity.setStreakFreezes(entity.getStreakFreezes() + 1);
            playerRepository.update(entity);
            return PurchaseResult.SUCCESS;
        });
    }
}
