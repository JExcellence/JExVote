package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.config.VoteShopItem;
import de.jexcellence.vote.database.entity.VotePlayerEntity;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vote-Token Shop: lets players spend vote points on configured rewards
 * (materials, crate keys, cosmetics - see the {@code vote-shop} section of
 * {@code rewards.yml}). This is the primary sink for vote points alongside
 * Streak Freezes.
 *
 * <p>Supports customizable sound effects and messages on purchase.
 *
 * @author JExcellence
 */
public class VoteShopService {

    /** Outcome of a {@link #purchase(Player, VoteShopItem)} attempt. */
    public enum PurchaseResult {
        SUCCESS,
        NOT_ENOUGH_POINTS,
        NO_PROFILE,
        /** Points were charged but the reward failed to deliver - the points were refunded. */
        GRANT_FAILED,
        /** Another purchase by the same player is still being processed; nothing was charged. */
        BUSY
    }

    private final VotePlayerRepository playerRepository;
    private final VoteRewardService rewardService;
    private final VoteRewardConfig rewardConfig;
    private final PlatformScheduler scheduler;
    private final Set<UUID> purchasesInFlight = ConcurrentHashMap.newKeySet();

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
     * Plays the configured purchase sound and messages.
     */
    public @NotNull CompletableFuture<PurchaseResult> purchase(@NotNull Player player, @NotNull VoteShopItem item) {
        UUID uuid = player.getUniqueId();
        if (!purchasesInFlight.add(uuid)) {
            return CompletableFuture.completedFuture(PurchaseResult.BUSY);
        }
        return chargeAndGrant(player, item)
                .whenComplete((result, ex) -> purchasesInFlight.remove(uuid));
    }

    /**
     * Charges the points and grants the reward. Only one call per player runs at a time (see
     * {@link #purchase}), so two quick clicks cannot both read the old balance and pay out twice.
     */
    private @NotNull CompletableFuture<PurchaseResult> chargeAndGrant(@NotNull Player player,
                                                                     @NotNull VoteShopItem item) {
        UUID uuid = player.getUniqueId();
        return playerRepository.findByUuidAsync(uuid).thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(PurchaseResult.NO_PROFILE);
            }
            VotePlayerEntity entity = opt.orElseThrow();
            if (entity.getVotePoints() < item.cost()) {
                return CompletableFuture.completedFuture(PurchaseResult.NOT_ENOUGH_POINTS);
            }
            entity.setVotePoints(entity.getVotePoints() - item.cost());
            playerRepository.update(entity);
            // Grant on the player's region thread and refund the points if the reward
            // fails to deliver, instead of charging fire-and-forget (which could leave
            // a player down points with nothing to show for it).
            CompletableFuture<PurchaseResult> result = new CompletableFuture<>();
            scheduler.runAtEntity(player, () ->
                    rewardService.grantChecked(player, item.reward()).whenComplete((granted, ex) -> {
                        if (ex != null || !Boolean.TRUE.equals(granted)) {
                            refundPoints(uuid, item.cost());
                            result.complete(PurchaseResult.GRANT_FAILED);
                            return;
                        }
                        playPurchaseSound(player, item);
                        sendPurchaseMessages(player, item);
                        result.complete(PurchaseResult.SUCCESS);
                    }));
            return result;
        });
    }

    /** Re-credits {@code cost} vote points after a failed reward delivery (re-reads to avoid a stale write). */
    private void refundPoints(@NotNull UUID uuid, int cost) {
        playerRepository.findByUuidAsync(uuid).thenAccept(opt -> opt.ifPresent(entity -> {
            entity.setVotePoints(entity.getVotePoints() + cost);
            playerRepository.update(entity);
        }));
    }

    /**
     * Plays the configured purchase sound for the shop item.
     */
    private void playPurchaseSound(@NotNull Player player, @NotNull VoteShopItem item) {
        VoteShopItem.ShopEffects effects = item.effects();
        Sound sound;
        try {
            sound = Sound.valueOf(effects.purchaseSound());
        } catch (IllegalArgumentException ignored) {
            // Invalid sound name - fall back to the level-up sound.
            sound = Sound.ENTITY_PLAYER_LEVELUP;
        }
        player.playSound(player, sound, effects.purchaseVolume(), effects.purchasePitch());
    }

    /**
     * Sends the configured purchase messages to the player.
     */
    private void sendPurchaseMessages(@NotNull Player player, @NotNull VoteShopItem item) {
        VoteShopItem.ShopEffects effects = item.effects();
        for (String message : effects.purchaseMessages()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(message));
        }
    }
}
