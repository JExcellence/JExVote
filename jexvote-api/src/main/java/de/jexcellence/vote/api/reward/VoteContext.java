package de.jexcellence.vote.api.reward;

import de.jexcellence.vote.api.model.VoteSnapshot;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * The context handed to a {@link VoteRewardProvider} when a vote is processed.
 *
 * @param uuid     the voter's UUID
 * @param name     the voter's best-known name
 * @param service  the vote service / site name
 * @param snapshot the voter's full vote stats <b>after</b> this vote applied
 * @param online   whether the voter is online on this backend right now
 * @param jackpot  whether this vote hit the jackpot / lucky path
 *
 * @author JExcellence
 * @since 1.0.0
 */
public record VoteContext(@NotNull UUID uuid, @NotNull String name, @NotNull String service,
                          @NotNull VoteSnapshot snapshot, boolean online, boolean jackpot) {}
