package de.jexcellence.vote;

import de.jexcellence.vote.api.JExVoteAPI;
import de.jexcellence.vote.api.VoteProvider;
import de.jexcellence.vote.api.reward.VoteRewardProvider;
import de.jexcellence.vote.service.VoteRewardProviderRegistry;
import org.jetbrains.annotations.NotNull;

public class JExVoteAPIImpl implements JExVoteAPI {

    /** Semver of the public api contract, independent of the plugin's 3.x/4.x version. */
    private static final String API_VERSION = "1.0.0";

    private static final String CAP_REWARD_SPI = "reward-spi";
    private static final String CAP_WRITE_HOOKS = "write-hooks";

    private final VoteProviderImpl provider;
    private final VoteRewardProviderRegistry rewardSpi;

    public JExVoteAPIImpl(@NotNull VoteProviderImpl provider,
                          @NotNull VoteRewardProviderRegistry rewardSpi) {
        this.provider = provider;
        this.rewardSpi = rewardSpi;
    }

    @Override
    public @NotNull VoteProvider provider() {
        return provider;
    }

    @Override
    public void registerRewardProvider(@NotNull VoteRewardProvider provider) {
        rewardSpi.register(provider);
    }

    @Override
    public void unregisterRewardProvider(@NotNull VoteRewardProvider provider) {
        rewardSpi.unregister(provider);
    }

    @Override
    public @NotNull String apiVersion() {
        return API_VERSION;
    }

    @Override
    public boolean supports(@NotNull String capability) {
        return switch (capability) {
            case CAP_REWARD_SPI, CAP_WRITE_HOOKS -> rewardSpi.enabled();
            default -> false;
        };
    }
}
