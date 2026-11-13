package de.jexcellence.vote.service;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Grants a list of rewards one after another, each started on the player's thread. In parallel, several coin
 * deposits hit the same economy account at once (optimistic-lock failures), and a grant chained after an async
 * deposit would otherwise touch the inventory off the player's thread. A failed grant is logged and the chain goes
 * on; the items a player who left never reached are handed back, so the caller can keep them for the next join.
 *
 * @author JExcellence
 * @since 3.2.11
 */
final class PlayerRewardChain {

    private final Logger logger;
    private final AtomicReference<PlayerThread> playerThread = new AtomicReference<>();

    PlayerRewardChain(@NotNull Logger logger) {
        this.logger = logger;
    }

    /** Runs a task on the thread that owns a player, or the retire task when the player is gone. */
    @FunctionalInterface
    interface PlayerThread {
        void run(@NotNull Player player, @NotNull Runnable task, @NotNull Runnable retired);
    }

    /** A grant that did not happen and whose item is handed back to be kept for the next join. */
    static class KeepForLaterException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        KeepForLaterException(@NotNull String message) {
            super(message);
        }
    }

    /** A grant that did not start because the player was no longer online. */
    static final class PlayerLeftException extends KeepForLaterException {

        private static final long serialVersionUID = 1L;

        PlayerLeftException() {
            super("player left before the reward was granted");
        }
    }

    void setPlayerThread(@NotNull PlayerThread thread) {
        playerThread.set(thread);
    }

    /**
     * Grants {@code items} in order.
     *
     * @param grant  starts the grant of one item
     * @param typeOf the reward type of an item, for the failure log
     * @param result receives each reached item with its result, {@code null} when the grant failed
     * @return the items that were not granted because the player left or the grant asked to keep them (see
     *         {@link KeepForLaterException}), in their original order
     */
    <I, T> @NotNull CompletableFuture<List<I>> grantInOrder(@NotNull Player player, @NotNull List<I> items,
                                                            @NotNull Function<I, CompletableFuture<T>> grant,
                                                            @NotNull Function<I, Object> typeOf,
                                                            @NotNull BiConsumer<I, T> result) {
        List<I> notReached = new ArrayList<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (I item : items) {
            chain = chain.thenCompose(ignored -> onPlayerThread(player, () -> grant.apply(item))
                    .handle((value, error) -> {
                        if (error == null) {
                            result.accept(item, value);
                        } else if (isKeptForLater(error)) {
                            notReached.add(item);
                        } else {
                            logFailedGrant(player, typeOf.apply(item), error);
                            result.accept(item, null);
                        }
                        return null;
                    }));
        }
        return chain.thenApply(ignored -> List.copyOf(notReached));
    }

    private <T> @NotNull CompletableFuture<T> onPlayerThread(@NotNull Player player,
                                                             @NotNull Supplier<CompletableFuture<T>> grant) {
        PlayerThread thread = playerThread.get();
        if (thread == null) {
            return startIfOnline(player, grant);
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            thread.run(player,
                    () -> startIfOnline(player, grant).whenComplete((value, error) -> complete(result, value, error)),
                    () -> result.completeExceptionally(new PlayerLeftException()));
        } catch (Exception ex) {
            result.completeExceptionally(ex);
        }
        return result;
    }

    private static <T> @NotNull CompletableFuture<T> startIfOnline(@NotNull Player player,
                                                                   @NotNull Supplier<CompletableFuture<T>> grant) {
        if (!player.isOnline()) {
            return CompletableFuture.failedFuture(new PlayerLeftException());
        }
        try {
            return grant.get();
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    private static <T> void complete(@NotNull CompletableFuture<T> result, @Nullable T value,
                                     @Nullable Throwable error) {
        if (error == null) {
            result.complete(value);
        } else {
            result.completeExceptionally(error);
        }
    }

    private static boolean isKeptForLater(@NotNull Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause instanceof KeepForLaterException;
    }

    private void logFailedGrant(@NotNull Player player, @Nullable Object typeId, @NotNull Throwable failure) {
        final String playerName = player.getName();
        logger.log(Level.WARNING, failure, () -> String.format("Vote %s reward failed for %s", typeId, playerName));
    }
}
