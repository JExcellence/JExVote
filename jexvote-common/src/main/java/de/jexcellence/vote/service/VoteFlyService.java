package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.database.entity.VotePlayerEntity;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Redeems vote points for flight. Two products share this service:
 * <ul>
 *   <li><b>Temporary flight</b> ({@link #redeem}) — spends {@link #costPoints}
 *       points for {@link #minutes} minutes via the {@link FlyBridge}.</li>
 *   <li><b>Event auto-fly perk</b> ({@link #redeemEventFly}) — a one-time
 *       {@link #eventFlyCost}-point purchase granting the permanent permission
 *       {@value #EVENT_FLY_PERMISSION}. JExOneblock's flight reconciler honours
 *       this node and auto-grants flight while any island event is active.</li>
 * </ul>
 * Mirrors the spend pattern of {@code StreakFreezeService}.
 *
 * @author JExcellence
 */
public final class VoteFlyService {

    /** Permission JExOneblock checks to auto-grant flight during island events. */
    public static final String EVENT_FLY_PERMISSION = "jexoneblock.eventfly";

    /** Outcome of a redemption attempt. */
    public enum FlyResult { SUCCESS, DISABLED, NO_PROFILE, NOT_ENOUGH_POINTS, UNAVAILABLE, ALREADY_OWNED }

    private final VotePlayerRepository repository;
    private final FlyBridge flyBridge;
    private final PlatformScheduler scheduler;
    private final boolean enabled;
    private final int costPoints;
    private final int minutes;
    private final int eventFlyCost;

    public VoteFlyService(@NotNull VotePlayerRepository repository,
                          @NotNull FlyBridge flyBridge,
                          @NotNull PlatformScheduler scheduler,
                          boolean enabled, int costPoints, int minutes, int eventFlyCost) {
        this.repository = repository;
        this.flyBridge = flyBridge;
        this.scheduler = scheduler;
        this.enabled = enabled;
        this.costPoints = Math.max(1, costPoints);
        this.minutes = Math.max(1, minutes);
        this.eventFlyCost = Math.max(1, eventFlyCost);
    }

    public int costPoints() { return costPoints; }
    public int minutes()    { return minutes; }
    public int eventFlyCost() { return eventFlyCost; }
    public boolean enabled() { return enabled; }

    /**
     * Attempts to redeem flight for the player. Verifies the profile + balance,
     * grants the flight (the grant itself applies on the sync context inside
     * JExEssentials), and only then deducts the points.
     */
    public @NotNull CompletableFuture<FlyResult> redeem(@NotNull Player player) {
        if (!enabled) {
            return CompletableFuture.completedFuture(FlyResult.DISABLED);
        }
        return repository.findByUuidAsync(player.getUniqueId()).thenApply(opt -> {
            if (opt.isEmpty()) {
                return FlyResult.NO_PROFILE;
            }
            final VotePlayerEntity entity = opt.orElseThrow();
            if (entity.getVotePoints() < costPoints) {
                return FlyResult.NOT_ENOUGH_POINTS;
            }
            // Grant first; only charge the player if the grant landed.
            if (!flyBridge.grantFly(player, minutes * 60L)) {
                return FlyResult.UNAVAILABLE;
            }
            entity.setVotePoints(entity.getVotePoints() - costPoints);
            repository.update(entity);
            return FlyResult.SUCCESS;
        });
    }

    /**
     * Attempts the one-time purchase of the permanent event auto-fly perk.
     * Grants the {@value #EVENT_FLY_PERMISSION} permission via a console
     * LuckPerms command (on the main thread) and charges {@link #eventFlyCost}
     * points only after the grant is dispatched. No-op if already owned.
     */
    public @NotNull CompletableFuture<FlyResult> redeemEventFly(@NotNull Player player) {
        if (!enabled) {
            return CompletableFuture.completedFuture(FlyResult.DISABLED);
        }
        if (player.hasPermission(EVENT_FLY_PERMISSION)) {
            return CompletableFuture.completedFuture(FlyResult.ALREADY_OWNED);
        }
        return repository.findByUuidAsync(player.getUniqueId()).thenApply(opt -> {
            if (opt.isEmpty()) {
                return FlyResult.NO_PROFILE;
            }
            final VotePlayerEntity entity = opt.orElseThrow();
            if (entity.getVotePoints() < eventFlyCost) {
                return FlyResult.NOT_ENOUGH_POINTS;
            }
            // LuckPerms console op must run on the main thread.
            scheduler.runSync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "lp user " + player.getName() + " permission set " + EVENT_FLY_PERMISSION + " true"));
            entity.setVotePoints(entity.getVotePoints() - eventFlyCost);
            repository.update(entity);
            return FlyResult.SUCCESS;
        });
    }
}
