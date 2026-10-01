package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.database.entity.PendingVoteRewardEntity;
import de.jexcellence.vote.database.repository.PendingVoteRewardRepository;
import de.jexcellence.vote.integration.IronmanGate;
import de.jexcellence.vote.integration.ProfileEvents;
import de.jexcellence.vote.integration.RewardProfile;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Vote rewards of the JExOneblock Season profile (owner decision 2026-10-01). {@code /vote} and the vote sites work
 * on every profile, but a Season profile cannot use vote crate keys, coupons or items. While the Season profile is
 * active, vote rewards are therefore kept in the pending reward queue, marked for the Normal profile, and granted
 * once the player's lowest-slot Normal profile is active: items and keys land in its inventory, coins in its island
 * bank. Without a Normal profile they wait until one exists. Normal and Ironman profiles are not affected.
 *
 * @author JExcellence
 * @since 3.2.10
 */
public final class NormalProfileRewards {

    /** Hands a batch of pending rows to the regular delivery and completes when every grant finished. */
    @FunctionalInterface
    public interface BatchDelivery {
        @NotNull CompletableFuture<Void> deliver(@NotNull List<PendingVoteRewardEntity> rows, boolean held);
    }

    /**
     * Where held rewards go and whether that profile is active now.
     *
     * @param profile the reward profile, or {@code null} when the player has no Normal profile (or profiles are off)
     * @param active  whether held rewards may be granted right now
     */
    private record Target(@Nullable RewardProfile profile, boolean active) {
    }

    private static final AtomicReference<NormalProfileRewards> INSTANCE = new AtomicReference<>();
    private static final String HELD_MARKER = "normal-profile::";
    private static final String COINS = "coins";
    private static final String KEY = "vote.normal-profile.";

    private final PendingVoteRewardRepository repository;
    private final PlatformScheduler scheduler;
    private final Logger logger;
    private final IronmanGate gate = IronmanGate.shared();
    private final Map<UUID, Long> bankIslands = new ConcurrentHashMap<>();
    private final Set<UUID> handOvers = ConcurrentHashMap.newKeySet();

    private NormalProfileRewards(@NotNull JavaPlugin plugin, @NotNull PendingVoteRewardRepository repository) {
        this.repository = repository;
        this.scheduler = PlatformScheduler.of(plugin);
        this.logger = plugin.getLogger();
    }

    /**
     * Creates the plugin-wide instance and hooks the JExOneblock profile switch.
     *
     * @param plugin     the owning plugin
     * @param repository the pending reward queue
     * @param handOver   delivers a player's pending rewards; called after every profile switch
     * @return the installed instance
     */
    public static @NotNull NormalProfileRewards install(@NotNull JavaPlugin plugin,
                                                        @NotNull PendingVoteRewardRepository repository,
                                                        @NotNull Consumer<Player> handOver) {
        NormalProfileRewards rewards = new NormalProfileRewards(plugin, repository);
        INSTANCE.set(rewards);
        if (ProfileEvents.register(plugin, handOver)) {
            plugin.getLogger().info("JExOneblock profiles detected - Season profile vote rewards go to the Normal profile");
        }
        return rewards;
    }

    /** The installed instance, or {@code null} before the plugin enabled it. */
    public static @Nullable NormalProfileRewards current() {
        return INSTANCE.get();
    }

    /** Whether the player's active profile is the Season profile, so vote rewards go to the Normal profile. */
    public boolean routes(@NotNull UUID player) {
        return gate.isSeasonProfile(player);
    }

    /**
     * Keeps a serialized reward blob for the player's Normal profile.
     *
     * @param player     the voter
     * @param source     the pending-entry source (service name, party, shop, streak claim)
     * @param rewardData the serialized rewards; ignored when {@code null}
     */
    public void hold(@NotNull UUID player, @NotNull String source, @Nullable String rewardData) {
        if (rewardData == null) {
            return;
        }
        repository.createAsync(new PendingVoteRewardEntity(player, HELD_MARKER + source, rewardData))
                .exceptionally(ex -> {
                    logger.log(Level.SEVERE, ex, () -> "Failed to keep vote rewards for the Normal profile of " + player);
                    return null;
                });
    }

    /** Whether the pending row waits for the Normal profile. */
    public static boolean isHeld(@NotNull PendingVoteRewardEntity row) {
        String serviceName = row.getServiceName();
        return serviceName != null && serviceName.startsWith(HELD_MARKER);
    }

    /** The source of a pending row without the Normal-profile marker. */
    public static @NotNull String sourceOf(@Nullable String serviceName) {
        if (serviceName == null) {
            return "";
        }
        return serviceName.startsWith(HELD_MARKER) ? serviceName.substring(HELD_MARKER.length()) : serviceName;
    }

    /**
     * Delivers what the active profile may receive and tells the player about the rest. Plain rows are granted
     * unless the Season profile is active; held rows only while the reward profile is active, with coins going to
     * its island bank. Call off the server thread.
     *
     * @param player   the online player
     * @param pending  every pending row of the player
     * @param delivery the regular delivery
     */
    public void handOver(@NotNull Player player, @NotNull List<PendingVoteRewardEntity> pending,
                         @NotNull BatchDelivery delivery) {
        List<PendingVoteRewardEntity> held = pending.stream().filter(NormalProfileRewards::isHeld).toList();
        List<PendingVoteRewardEntity> plain = pending.stream().filter(row -> !isHeld(row)).toList();
        boolean season = routes(player.getUniqueId());
        if (held.isEmpty() && !season) {
            delivery.deliver(plain, false);
            return;
        }
        target(player.getUniqueId()).thenAccept(target -> dispatch(player, plain, held, target, delivery))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, ex, () -> "Failed to resolve the reward profile of " + player.getName());
                    return null;
                });
    }

    /** Tells the player right after a Season-profile vote where its rewards went. */
    public void tellWhere(@NotNull Player player) {
        gate.rewardProfile(player.getUniqueId()).thenAccept(profile ->
                scheduler.runAtEntity(player, () -> tell(player, profile.orElse(null), 0)));
    }

    /**
     * Pays a coin reward of a held delivery into the reward profile's island bank.
     *
     * @return {@code true} when the deposit went to the bank; {@code false} leaves it to the regular economy
     */
    public boolean depositToBank(@NotNull UUID player, @NotNull String currency, double amount) {
        Long island = bankIslands.get(player);
        if (island == null || !COINS.equalsIgnoreCase(currency)) {
            return false;
        }
        return gate.depositToIslandBank(island, COINS, Math.round(amount));
    }

    private void dispatch(@NotNull Player player, @NotNull List<PendingVoteRewardEntity> plain,
                          @NotNull List<PendingVoteRewardEntity> held, @NotNull Target target,
                          @NotNull BatchDelivery delivery) {
        boolean season = routes(player.getUniqueId());
        List<PendingVoteRewardEntity> waiting = new ArrayList<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        if (season) {
            waiting.addAll(plain);
        } else if (!plain.isEmpty()) {
            chain = delivery.deliver(plain, false);
        }
        if (target.active() && !season) {
            chain.thenCompose(ignored -> deliverHeld(player, held, target, delivery));
        } else {
            waiting.addAll(held);
        }
        if (!waiting.isEmpty()) {
            int count = waiting.size();
            scheduler.runAtEntity(player, () -> tell(player, target.profile(), count));
        }
    }

    private @NotNull CompletableFuture<Void> deliverHeld(@NotNull Player player,
                                                         @NotNull List<PendingVoteRewardEntity> held,
                                                         @NotNull Target target, @NotNull BatchDelivery delivery) {
        UUID uuid = player.getUniqueId();
        if (held.isEmpty() || !handOvers.add(uuid)) {
            return CompletableFuture.completedFuture(null);
        }
        Long island = target.profile() == null ? null : target.profile().islandId();
        if (island != null) {
            bankIslands.put(uuid, island);
        }
        return delivery.deliver(held, true).whenComplete((ignored, ex) -> {
            bankIslands.remove(uuid);
            handOvers.remove(uuid);
        });
    }

    private @NotNull CompletableFuture<Target> target(@NotNull UUID player) {
        return gate.rewardProfile(player).thenApply(found -> {
            RewardProfile profile = found.orElse(null);
            Long active = gate.activeProfileId(player);
            boolean deliverable = profile == null
                    ? active == null && !routes(player)
                    : Objects.equals(profile.id(), active);
            return new Target(profile, deliverable);
        });
    }

    /**
     * Sends where the rewards are: {@code sent} right after a vote, {@code held} as the reminder for waiting
     * entries, {@code waiting} when no Normal profile exists yet.
     */
    private static void tell(@NotNull Player player, @Nullable RewardProfile profile, int waitingEntries) {
        R18nManager r18n = R18nManager.getInstance();
        if (profile == null) {
            r18n.msg(KEY + "waiting").prefix().send(player);
            return;
        }
        String key = waitingEntries > 0 ? "held" : "sent";
        r18n.msg(KEY + key).prefix().with("slot", String.valueOf(profile.slot())).send(player);
    }
}
