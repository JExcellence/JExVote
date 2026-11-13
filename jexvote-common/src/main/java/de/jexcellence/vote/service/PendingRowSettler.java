package de.jexcellence.vote.service;

import de.jexcellence.vote.database.entity.PendingVoteRewardEntity;
import de.jexcellence.vote.database.repository.PendingVoteRewardRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Settles a paid pending row: stores what the player did not get (when they left during the payout) as a new row
 * first, then removes the paid row. A row whose removal failed is remembered in {@code paid-unremoved-vote-rows.txt},
 * so it is never paid a second time, not even after a restart, and its removal is retried on the player's next
 * delivery. If storing the remainder fails, the paid row is still removed (paying it again would double the delivered
 * part) and the remainder is kept in {@code unstored-vote-leftovers.txt} until it can be stored.
 *
 * @author JExcellence
 * @since 3.2.11
 */
final class PendingRowSettler {

    private static final String FILE_NAME = "paid-unremoved-vote-rows.txt";
    private static final String LEFTOVER_FILE_NAME = "unstored-vote-leftovers.txt";
    private static final String FIELD_SEPARATOR = "|";
    private static final int LEFTOVER_FIELDS = 3;

    private final PendingVoteRewardRepository repository;
    private final Logger logger;
    private final LineSetFile paidButNotRemoved;
    private final LineSetFile unstoredLeftovers;

    PendingRowSettler(@NotNull PendingVoteRewardRepository repository, @NotNull Logger logger,
                      @NotNull File dataFolder) {
        this.repository = repository;
        this.logger = logger;
        this.paidButNotRemoved = new LineSetFile(dataFolder, FILE_NAME, logger, PendingRowSettler::isRowId);
        this.unstoredLeftovers = new LineSetFile(dataFolder, LEFTOVER_FILE_NAME, logger,
                line -> parseLeftover(line) != null);
    }

    /** Rewards a player missed whose new row could not be stored; kept in a file until the row is stored. */
    private record Leftover(@NotNull UUID player, @NotNull String serviceName, @NotNull String rewardData) {

        @NotNull String line() {
            Base64.Encoder encoder = Base64.getEncoder();
            return player + FIELD_SEPARATOR + encoder.encodeToString(serviceName.getBytes(StandardCharsets.UTF_8))
                    + FIELD_SEPARATOR + encoder.encodeToString(rewardData.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static @Nullable Leftover parseLeftover(@NotNull String line) {
        String[] fields = line.split("\\" + FIELD_SEPARATOR, LEFTOVER_FIELDS);
        if (fields.length != LEFTOVER_FIELDS) {
            return null;
        }
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            return new Leftover(UUID.fromString(fields[0]),
                    new String(decoder.decode(fields[1]), StandardCharsets.UTF_8),
                    new String(decoder.decode(fields[2]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean isRowId(@NotNull String line) {
        try {
            Long.parseLong(line);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /**
     * Settles one paid row.
     *
     * @param remaining what the player did not get, {@code null} when everything arrived; a value equal to the row's
     *                  data means nothing was taken and the row stays as it is
     * @return completes once the row is settled or the failure is recorded; never fails
     */
    @NotNull CompletableFuture<Void> settle(@NotNull PendingVoteRewardEntity row, @Nullable String remaining) {
        if (Objects.equals(row.getRewardData(), remaining)) {
            return CompletableFuture.completedFuture(null);
        }
        return storeRemaining(row, remaining).thenCompose(ignored -> remove(row));
    }

    private @NotNull CompletableFuture<Void> remove(@NotNull PendingVoteRewardEntity row) {
        return repository.deleteAsync(row.getId()).handle((deleted, error) -> {
            if (error != null) {
                rememberUnremoved(row, error);
            }
            return null;
        });
    }

    /**
     * Drops rows that were paid but could not be removed and retries their removal; a row stays skipped until the
     * removal went through. Also retries storing the player's leftovers whose row could not be stored; a stored
     * leftover is paid on the next delivery.
     *
     * @return completes with the rows that may be paid, after the retries finished
     */
    @NotNull CompletableFuture<List<PendingVoteRewardEntity>> payable(@NotNull UUID player,
                                                                      @NotNull List<PendingVoteRewardEntity> rows) {
        List<PendingVoteRewardEntity> payable = new ArrayList<>(rows.size());
        List<CompletableFuture<Void>> retries = new ArrayList<>();
        for (PendingVoteRewardEntity row : rows) {
            if (row.getId() != null && paidButNotRemoved.contains(String.valueOf(row.getId()))) {
                retries.add(retryRemoval(row));
            } else {
                payable.add(row);
            }
        }
        for (String line : unstoredLeftovers.all()) {
            Leftover leftover = parseLeftover(line);
            if (leftover != null && leftover.player().equals(player)) {
                retries.add(retryLeftover(line, leftover));
            }
        }
        return CompletableFuture.allOf(retries.toArray(CompletableFuture[]::new)).thenApply(ignored -> payable);
    }

    private @NotNull CompletableFuture<Void> retryRemoval(@NotNull PendingVoteRewardEntity row) {
        String rowKey = String.valueOf(row.getId());
        return repository.deleteAsync(row.getId()).handle((deleted, error) -> {
            if (error == null) {
                paidButNotRemoved.remove(rowKey);
            } else {
                logger.log(Level.WARNING, error, () -> "Paid pending vote reward " + rowKey
                        + " still could not be removed - it stays skipped");
            }
            return null;
        });
    }

    private @NotNull CompletableFuture<Void> retryLeftover(@NotNull String line, @NotNull Leftover leftover) {
        return repository.createAsync(
                new PendingVoteRewardEntity(leftover.player(), leftover.serviceName(), leftover.rewardData()))
                .handle((created, error) -> {
                    if (error == null) {
                        unstoredLeftovers.remove(line);
                    } else {
                        logger.log(Level.WARNING, error, () -> "Vote leftovers of " + leftover.player()
                                + " still could not be stored - kept in " + LEFTOVER_FILE_NAME);
                    }
                    return null;
                });
    }

    private void rememberUnremoved(@NotNull PendingVoteRewardEntity row, @NotNull Throwable error) {
        if (row.getId() != null) {
            paidButNotRemoved.add(String.valueOf(row.getId()));
        }
        logger.log(Level.SEVERE, error, () -> String.format("Paid pending vote reward %s of %s could not be removed "
                + "- it is skipped until removed",
                row.getId(), row.getPlayerUuid()));
    }

    private @NotNull CompletableFuture<Void> storeRemaining(@NotNull PendingVoteRewardEntity row,
                                                           @Nullable String remaining) {
        if (remaining == null) {
            return CompletableFuture.completedFuture(null);
        }
        return repository.createAsync(new PendingVoteRewardEntity(row.getPlayerUuid(), row.getServiceName(), remaining))
                .handle((created, error) -> {
                    if (error != null) {
                        unstoredLeftovers.add(
                                new Leftover(row.getPlayerUuid(), row.getServiceName(), remaining).line());
                        logger.log(Level.SEVERE, error, () -> String.format("Undelivered vote rewards of %s could not "
                                + "be stored again - kept in %s and retried on the next delivery",
                                row.getPlayerUuid(), LEFTOVER_FILE_NAME));
                    }
                    return null;
                });
    }
}
