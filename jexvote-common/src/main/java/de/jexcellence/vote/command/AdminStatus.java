package de.jexcellence.vote.command;

import de.jexcellence.vote.service.VotePartyService;
import org.jetbrains.annotations.Nullable;

/**
 * Live plugin state the admin commands report and act on. Implemented by the plugin bootstrap, so the command
 * handler does not need a reference to every subsystem.
 *
 * @author JExcellence
 * @since 3.3.0
 */
public interface AdminStatus {

    /** @return whether the built-in Votifier listener is accepting connections. */
    boolean votifierRunning();

    /** @return whether the embedded REST API is serving. */
    boolean restApiRunning();

    /** @return whether the PlaceholderAPI expansion is registered. */
    boolean placeholdersHooked();

    /** @return whether Floodgate was found and Bedrock players get forms. */
    boolean bedrockFormsHooked();

    /** @return the vote party service, or {@code null} when the party did not start. */
    @Nullable VotePartyService party();

    /** @return how many sites {@code sites.yml} defines, before the edition limit. */
    int configuredSiteCount();

    /** @return how many shop items {@code rewards.yml} defines. */
    int shopItemCount();

    /** Reloads config.yml, rewards.yml and sites.yml and applies them. */
    void reload();
}
