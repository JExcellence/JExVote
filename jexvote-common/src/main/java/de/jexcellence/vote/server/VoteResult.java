package de.jexcellence.vote.server;

import de.jexcellence.vote.model.Vote;
import org.jetbrains.annotations.NotNull;

public record VoteResult(
        @NotNull Vote vote,
        @NotNull Protocol protocol
) {
    public enum Protocol {
        V1,
        V2
    }
}
