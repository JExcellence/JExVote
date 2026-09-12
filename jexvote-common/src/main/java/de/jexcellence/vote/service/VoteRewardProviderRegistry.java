package de.jexcellence.vote.service;

import de.jexcellence.vote.api.reward.VoteContext;
import de.jexcellence.vote.api.reward.VoteRewardDescriptor;
import de.jexcellence.vote.api.reward.VoteRewardProvider;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds the registered {@link VoteRewardProvider}s (the V2 reward SPI). Providers
 * are always accepted, but when the SPI is disabled (Free edition) they are inert
 * and a one-time warning is logged - so an integrator can build against Free and
 * have it light up on Premium.
 *
 * @author JExcellence
 */
public final class VoteRewardProviderRegistry {

    private final Logger logger;
    private final boolean enabled;
    private final AtomicBoolean warnedInert = new AtomicBoolean(false);
    private final List<VoteRewardProvider> providers = new CopyOnWriteArrayList<>();

    public VoteRewardProviderRegistry(@NotNull Logger logger, boolean enabled) {
        this.logger = logger;
        this.enabled = enabled;
    }

    public void register(@NotNull VoteRewardProvider provider) {
        providers.add(provider);
        if (!enabled && warnedInert.compareAndSet(false, true)) {
            logger.log(Level.WARNING, () ->
                    "A vote reward-provider was registered, but the reward SPI is a Premium feature - "
                            + "providers are inert on the Free edition.");
        }
    }

    public void unregister(@NotNull VoteRewardProvider provider) {
        providers.remove(provider);
    }

    public boolean enabled() {
        return enabled;
    }

    /** True when the SPI is enabled and at least one provider is registered. */
    public boolean isActive() {
        return enabled && !providers.isEmpty();
    }

    /**
     * Collects the descriptors from every provider for {@code context}. Returns an
     * empty list when the SPI is disabled or has no providers. A misbehaving
     * provider is isolated - its exception is logged and the others still contribute.
     *
     * @param context the vote context
     * @return the aggregated descriptors (never {@code null})
     */
    public @NotNull List<VoteRewardDescriptor> collect(@NotNull VoteContext context) {
        if (!isActive()) {
            return List.of();
        }
        List<VoteRewardDescriptor> out = new ArrayList<>();
        for (VoteRewardProvider provider : providers) {
            try {
                List<VoteRewardDescriptor> rewards = provider.rewardsFor(context);
                if (rewards != null) {
                    out.addAll(rewards);
                }
            } catch (Exception ex) {
                logger.log(Level.WARNING, ex, () ->
                        "A vote reward-provider threw while describing rewards for " + context.name());
            }
        }
        return out;
    }
}
