package de.jexcellence.vote.service;

import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.api.event.VoteRewardClaimedEvent;
import de.jexcellence.vote.api.reward.VoteRewardDescriptor;
import de.jexcellence.vote.database.entity.PendingVoteRewardEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pays a processed vote. An online voter gets the items first, on their own thread; the vote commands, bonus
 * rewards, messages and {@link VoteRewardClaimedEvent} follow once the items landed. Anything a voter misses because
 * they left (before the payout, between items and commands, or in the middle of the items) is kept as a pending row
 * for the next join.
 *
 * @author JExcellence
 * @since 3.2.11
 */
final class OnlineVotePayout {

    private final Logger logger;
    private final VoteRewardService rewards;
    private final VoteBroadcastService broadcasts;
    private final VoteCommandSet commands;
    private final Hooks hooks;

    /**
     * The platform and storage pieces the payout uses, injected so the payout can be tested without a server.
     *
     * @param playerTasks  runs a task on the thread that owns a player, or the retire task when they are gone
     * @param storePending stores a pending row off the calling thread
     * @param bonusQueue   queues bonus reward descriptors for the next join
     * @param executor     supplies the bonus descriptor executor; supplies {@code null} while it is not wired
     * @param events       fires a Bukkit event
     */
    record Hooks(@NotNull PlayerRewardChain.PlayerThread playerTasks,
                 @NotNull Consumer<PendingVoteRewardEntity> storePending,
                 @NotNull BonusQueue bonusQueue,
                 @NotNull Supplier<VoteDescriptorExecutor> executor,
                 @NotNull Consumer<Event> events) {
    }

    /** Queues bonus reward descriptors of a voter for the next join. */
    @FunctionalInterface
    interface BonusQueue {
        void queue(@NotNull UUID player, @NotNull String service, @NotNull List<VoteRewardDescriptor> bonus);
    }

    OnlineVotePayout(@NotNull Logger logger, @NotNull VoteRewardService rewards,
                     @NotNull VoteBroadcastService broadcasts, @NotNull VoteCommandSet commands,
                     @NotNull Hooks hooks) {
        this.logger = logger;
        this.rewards = rewards;
        this.broadcasts = broadcasts;
        this.commands = commands;
        this.hooks = hooks;
    }

    /** Starts the payout of an online voter on their thread; a voter who is gone by then gets it on the next join. */
    void pay(@NotNull PaidVote paid) {
        Player player = Objects.requireNonNull(paid.player());
        hooks.playerTasks().run(player, () -> deliver(paid), () -> queueOffline(paid));
    }

    /** Stores a vote's rewards, commands and bonus rewards as pending rows for the next join. */
    void queueOffline(@NotNull PaidVote paid) {
        List<String> queued = queuedCommands(paid);
        keepForNextJoin(paid, rewards.serializeRewards(paid.service(), paid.streak(), queued));
    }

    private @NotNull List<String> queuedCommands(@NotNull PaidVote paid) {
        return commands.queuedFor(paid.service(), paid.streak(), paid.firstDailyBonus());
    }

    private void deliver(@NotNull PaidVote paid) {
        Player player = Objects.requireNonNull(paid.player());
        if (!player.isOnline()) {
            queueOffline(paid);
            return;
        }
        rewards.grantRewards(player, paid.service(), paid.streak()).whenComplete((ignored, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, error, () -> "Vote reward payout failed for " + paid.uuid());
            }
            hooks.playerTasks().run(player, () -> finish(paid), () -> keepFollowUps(paid));
        });
    }

    private void finish(@NotNull PaidVote paid) {
        Player player = Objects.requireNonNull(paid.player());
        if (!player.isOnline()) {
            keepFollowUps(paid);
            return;
        }
        try {
            runFollowUps(player, paid);
        } catch (Exception ex) {
            logger.log(Level.WARNING, ex, () -> "Vote follow-ups failed for " + paid.uuid());
        }
    }

    private void runFollowUps(@NotNull Player player, @NotNull PaidVote paid) {
        rewards.runVoteCommands(player, paid.service(), paid.streak());
        grantBonus(player, paid);
        rewards.runCommands(player, commands.streakFor(paid.service(), paid.streak()));
        if (paid.firstDailyBonus()) {
            rewards.runCommands(player, commands.daily());
        }
        notifyVoter(player, paid);
        hooks.events().accept(new VoteRewardClaimedEvent(paid.uuid(), paid.service(), paid.snapshot()));
    }

    private void grantBonus(@NotNull Player player, @NotNull PaidVote paid) {
        if (paid.bonus().isEmpty()) {
            return;
        }
        VoteDescriptorExecutor executor = hooks.executor().get();
        if (executor == null) {
            logBonusDropped(logger, paid.uuid(), paid.bonus().size());
            return;
        }
        executor.grant(player, paid.bonus());
    }

    private void notifyVoter(@NotNull Player player, @NotNull PaidVote paid) {
        int streak = paid.streak();
        broadcasts.notifyPlayer(player, paid.service(), streak);
        broadcasts.playVoteEffects(player, streak, rewards.getStreakRewards().containsKey(streak));
        if (rewards.hasGuaranteedRewards()) {
            broadcasts.notifyGuaranteedReward(player, rewards.getGuaranteedRewards());
        }
        PaidVote.FreezeNotice freezes = paid.freezes();
        if (freezes.freshGrant() > 0) {
            R18nManager.getInstance().msg("vote.freeze.granted").prefix()
                    .with("amount", String.valueOf(freezes.freshGrant()))
                    .send(player);
        }
        if (freezes.consumed() > 0) {
            R18nManager.getInstance().msg("vote.freeze.saved").prefix()
                    .with("consumed", String.valueOf(freezes.consumed()))
                    .with("remaining", String.valueOf(freezes.remaining()))
                    .with("streak", String.valueOf(streak))
                    .send(player);
        }
    }

    /** The vote commands and bonus rewards of a voter who left right after the items: kept for the next join. */
    private void keepFollowUps(@NotNull PaidVote paid) {
        keepForNextJoin(paid, rewards.serializeVoteCommands(paid.service(), paid.streak(), queuedCommands(paid)));
    }

    /** Logs bonus rewards that were dropped because the reward executor is not wired yet. */
    static void logBonusDropped(@NotNull Logger logger, @NotNull UUID player, int count) {
        logger.log(Level.WARNING, () -> String.format(
                "%d bonus vote reward(s) of %s dropped: the reward executor is not ready", count, player));
    }

    /** Stores a pending row (when there is one) and queues the bonus rewards for the next join. */
    private void keepForNextJoin(@NotNull PaidVote paid, @Nullable String rewardData) {
        if (rewardData != null) {
            hooks.storePending().accept(new PendingVoteRewardEntity(paid.uuid(), paid.service(), rewardData));
        }
        hooks.bonusQueue().queue(paid.uuid(), paid.service(), paid.bonus());
    }
}
