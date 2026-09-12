package de.jexcellence.vote.api;

import de.jexcellence.vote.api.reward.VoteRewardProvider;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface JExVoteAPI {

    @NotNull VoteProvider provider();

    /**
     * Registers a {@link VoteRewardProvider} that contributes additive rewards for
     * each vote. Always accepted; on the Free edition providers are inert (a warning
     * is logged) - feature-detect with {@link #supports(String)} and {@code "reward-spi"}.
     *
     * @param provider the provider to register
     */
    void registerRewardProvider(@NotNull VoteRewardProvider provider);

    /**
     * Unregisters a previously-registered {@link VoteRewardProvider}. No-op if it
     * was never registered.
     *
     * @param provider the provider to remove
     */
    void unregisterRewardProvider(@NotNull VoteRewardProvider provider);

    /**
     * The semantic version of this public API contract (independent of the plugin
     * version), for consumers that gate on API capabilities.
     *
     * @return the api semver, e.g. {@code "1.0.0"}
     */
    @NotNull String apiVersion();

    /**
     * Feature-detection for runtime-gated capabilities so a consumer degrades
     * gracefully instead of hitting an inert path. Known capabilities:
     * {@code "reward-spi"}, {@code "write-hooks"}, {@code "proxy-sync"}, {@code "rest"}.
     *
     * @param capability the capability id
     * @return {@code true} if this running instance supports it
     */
    boolean supports(@NotNull String capability);

    static @NotNull JExVoteAPI get() {
        RegisteredServiceProvider<JExVoteAPI> rsp =
                Bukkit.getServicesManager().getRegistration(JExVoteAPI.class);
        if (rsp == null) {
            throw new IllegalStateException("JExVote is not loaded");
        }
        return rsp.getProvider();
    }

    static @Nullable JExVoteAPI getOrNull() {
        RegisteredServiceProvider<JExVoteAPI> rsp =
                Bukkit.getServicesManager().getRegistration(JExVoteAPI.class);
        return rsp != null ? rsp.getProvider() : null;
    }
}
