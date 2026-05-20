package de.jexcellence.vote;

import de.jexcellence.vote.api.JExVoteAPI;
import de.jexcellence.vote.api.VoteProvider;
import org.jetbrains.annotations.NotNull;

public class JExVoteAPIImpl implements JExVoteAPI {

    private final VoteProviderImpl provider;

    public JExVoteAPIImpl(@NotNull VoteProviderImpl provider) {
        this.provider = provider;
    }

    @Override
    public @NotNull VoteProvider provider() {
        return provider;
    }
}
