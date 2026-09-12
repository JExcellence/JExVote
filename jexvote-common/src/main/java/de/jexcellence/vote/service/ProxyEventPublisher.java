package de.jexcellence.vote.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Publishes a network-wide vote event to the cross-backend outbox. Kept as a
 * narrow interface so producers (e.g. {@link VotePartyService}) depend on the act
 * of publishing, not on the concrete {@link OutboxProxyEventBus}.
 *
 * @author JExcellence
 */
@FunctionalInterface
public interface ProxyEventPublisher {

    /**
     * Publishes an event. Implementations must be safe to call from any thread and
     * must never throw into the caller.
     *
     * @param type    the event type (see {@link ProxyEventTypes})
     * @param payload optional small payload (e.g. {@code "17:100"} or {@code "3"})
     */
    void publish(@NotNull String type, @Nullable String payload);
}
