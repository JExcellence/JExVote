package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.config.VoteShopItem;
import de.jexcellence.vote.database.entity.VotePlayerEntity;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Vote-Token Shop: lets players spend vote points on configured rewards
 * (materials, crate keys, cosmetics — see the {@code vote-shop} section of
 * {@code rewards.yml}). This is the primary sink for vote points alongside
 * Streak Freezes.
 *
 * @author JExcellence
 */
public class VoteShopService {

    /** Outcome of a {@link #purchase(Player, VoteShopItem)} attempt. */
    public enum PurchaseResult {
        SUCCESS,
        NOT_ENOUGH_POINTS,
        NO_PROFILE
    }

    private final VotePlayerRepository playerRepository;
    private final VoteRewardService rewardService;
    private final VoteRewardConfig rewardConfig;
    private final PlatformScheduler scheduler;

    public VoteShopService(@NotNull JavaPlugin plugin,
                           @NotNull VotePlayerRepository playerRepository,
                           @NotNull VoteRewardService rewardService,
                           @NotNull VoteRewardConfig rewardConfig) {
        this.playerRepository = playerRepository;
        this.rewardService = rewardService;
        this.rewardConfig = rewardConfig;
        this.scheduler = PlatformScheduler.of(plugin);
    }

    /** All configured shop entries (read live so {@code /jexvote reload} applies). */
    public @NotNull List<VoteShopItem> items() {
        return rewardConfig.getVoteShopItems();
    }

    /** Finds a shop entry by its id. */
    public @NotNull Optional<VoteShopItem> byId(@NotNull String id) {
        return items().stream().filter(i -> i.id().equals(id)).findFirst();
    }

    /** Returns the player's current vote-point balance (0 if no profile yet). */
    public @NotNull CompletableFuture<Integer> getPoints(@NotNull UUID uuid) {
        return playerRepository.findByUuidAsync(uuid)
                .thenApply(opt -> opt.map(VotePlayerEntity::getVotePoints).orElse(0));
    }

    /**
     * Attempts to buy {@code item} for {@code player}, charging vote points and
     * granting the reward on success (on the player's region thread).
     */
    public @NotNull CompletableFuture<PurchaseResult> purchase(@NotNull Player player, @NotNull VoteShopItem item) {
        UUID uuid = player.getUniqueId();
        return playerRepository.findByUuidAsync(uuid).thenApply(opt -> {
            Optional<VotePlayerEntity> maybe = opt;
            if (maybe.isEmpty()) {
                return PurchaseResult.NO_PROFILE;
            }
            VotePlayerEntity entity = maybe.orElseThrow();
            if (entity.getVotePoints() < item.cost()) {
                return PurchaseResult.NOT_ENOUGH_POINTS;
            }
            entity.setVotePoints(entity.getVotePoints() - item.cost());
            playerRepository.update(entity);
            scheduler.runAtEntity(player, () ->
                    rewardService.grantRewardList(player, List.of(item.reward())));
            return PurchaseResult.SUCCESS;
        });
    }
}
