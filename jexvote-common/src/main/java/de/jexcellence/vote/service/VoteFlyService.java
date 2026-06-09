package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.vote.config.FlyServiceConfig;
import de.jexcellence.vote.database.entity.VotePlayerEntity;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Redeems vote points for flight. Two products share this service:
 * <ul>
 *   <li><b>Temporary flight</b> ({@link #redeem}) — spends {@link FlyServiceConfig#getCostPoints()}
 *       points for {@link FlyServiceConfig#getMinutes()} minutes via the {@link FlyBridge}.</li>
 *   <li><b>Event auto-fly perk</b> ({@link #redeemEventFly}) — a one-time
 *       {@link FlyServiceConfig#getEventFlyCostPoints()}-point purchase granting the permanent permission
 *       {@value #EVENT_FLY_PERMISSION}. JExOneblock's flight reconciler honours
 *       this node and auto-grants flight while any island event is active.</li>
 * </ul>
 * Mirrors the spend pattern of {@code StreakFreezeService}.
 *
 * <p>Configuration is loaded from {@link FlyServiceConfig} for full customization.
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
    private final FlyServiceConfig config;

    public VoteFlyService(@NotNull VotePlayerRepository repository,
                          @NotNull FlyBridge flyBridge,
                          @NotNull PlatformScheduler scheduler,
                          @NotNull FlyServiceConfig config) {
        this.repository = repository;
        this.flyBridge = flyBridge;
        this.scheduler = scheduler;
        this.config = config;
    }

    public int costPoints() { return config.getCostPoints(); }
    public int minutes()    { return config.getMinutes(); }
    public int eventFlyCost() { return config.getEventFlyCostPoints(); }
    public boolean enabled() { return config.isEnabled(); }

    /**
     * Attempts to redeem flight for the player. Verifies the profile + balance,
     * grants the flight (the grant itself applies on the sync context inside
     * JExEssentials), and only then deducts the points.
     */
    public @NotNull CompletableFuture<FlyResult> redeem(@NotNull Player player) {
        if (!config.isEnabled()) {
            return CompletableFuture.completedFuture(FlyResult.DISABLED);
        }
        return repository.findByUuidAsync(player.getUniqueId()).thenApply(opt -> {
            if (opt.isEmpty()) {
                return FlyResult.NO_PROFILE;
            }
            final VotePlayerEntity entity = opt.orElseThrow();
            if (entity.getVotePoints() < config.getCostPoints()) {
                return FlyResult.NOT_ENOUGH_POINTS;
            }
            // Grant first; only charge the player if the grant landed.
            if (!flyBridge.grantFly(player, config.getMinutes() * 60L)) {
                return FlyResult.UNAVAILABLE;
            }
            entity.setVotePoints(entity.getVotePoints() - config.getCostPoints());
            repository.update(entity);
            return FlyResult.SUCCESS;
        });
    }

    /**
     * Attempts the one-time purchase of the permanent event auto-fly perk.
     * Grants the {@value #EVENT_FLY_PERMISSION} permission via a console
     * LuckPerms command (on the main thread) and charges {@link FlyServiceConfig#getEventFlyCostPoints()}
     * points only after the grant is dispatched. No-op if already owned.
     */
    public @NotNull CompletableFuture<FlyResult> redeemEventFly(@NotNull Player player) {
        if (!config.isEnabled()) {
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
            if (entity.getVotePoints() < config.getEventFlyCostPoints()) {
                return FlyResult.NOT_ENOUGH_POINTS;
            }
            // LuckPerms console op must run on the main thread.
            scheduler.runSync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "lp user " + player.getName() + " permission set " + EVENT_FLY_PERMISSION + " true"));
            entity.setVotePoints(entity.getVotePoints() - config.getEventFlyCostPoints());
            repository.update(entity);
            return FlyResult.SUCCESS;
        });
    }
}
