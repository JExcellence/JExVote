package de.jexcellence.vote.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface JExVoteAPI {

    @NotNull VoteProvider provider();

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
