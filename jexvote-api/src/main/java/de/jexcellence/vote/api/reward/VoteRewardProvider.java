package de.jexcellence.vote.api.reward;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * SPI a plugin implements to contribute rewards for a vote without depending on
 * JExVote internals. Register it via
 * {@link de.jexcellence.vote.api.JExVoteAPI#registerRewardProvider(VoteRewardProvider)}.
 *
 * <p>The returned rewards are <b>additive</b> to the configured rewards and are
 * granted through JExVote's existing safe (sequential, economy-lock-aware)
 * pipeline. The method is called during vote processing, possibly off the main
 * thread - keep it fast and free of Bukkit API calls; just describe the rewards.
 *
 * <p><b>Runtime gating:</b> registration is always accepted, but on the Free
 * edition providers are inert (a one-time warning is logged). Feature-detect with
 * {@link de.jexcellence.vote.api.JExVoteAPI#supports(String)} and the capability
 * {@code "reward-spi"} so a plugin can develop against Free and light up on Premium.
 *
 * @author JExcellence
 * @since 1.0.0
 */
@FunctionalInterface
public interface VoteRewardProvider {

    /**
     * Describes the rewards this provider grants for {@code context}.
     *
     * @param context the vote context (voter, service, post-vote snapshot, flags)
     * @return the reward descriptors to grant; empty for none (never {@code null})
     */
    @NotNull List<VoteRewardDescriptor> rewardsFor(@NotNull VoteContext context);
}
