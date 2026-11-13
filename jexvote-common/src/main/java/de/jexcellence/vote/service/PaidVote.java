package de.jexcellence.vote.service;

import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.api.reward.VoteRewardDescriptor;
import de.jexcellence.vote.model.Vote;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * One processed vote and what its payout needs.
 *
 * @param player          the voter while online, {@code null} for a vote cast offline
 * @param vote            the vote
 * @param uuid            the voter
 * @param snapshot        the voter's stats after the vote
 * @param streak          the streak after the vote
 * @param firstDailyBonus whether this is the first vote of the day with a daily bonus configured
 * @param freezes         the Streak Freeze lines the voter is told
 * @param bonus           the bonus reward descriptors of the vote
 * @author JExcellence
 * @since 3.2.11
 */
record PaidVote(@Nullable Player player, @NotNull Vote vote, @NotNull UUID uuid, @NotNull VoteSnapshot snapshot,
                int streak, boolean firstDailyBonus, @NotNull FreezeNotice freezes,
                @NotNull List<VoteRewardDescriptor> bonus) {

    @NotNull String service() {
        return vote.serviceName();
    }

    /** The Streak Freeze lines an online voter is told after the vote. */
    record FreezeNotice(int consumed, int remaining, int freshGrant) {

        static final FreezeNotice NONE = new FreezeNotice(0, 0, 0);
    }
}
