package de.jexcellence.vote.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the {@code site-rewards} entry of a vote. Operators key {@code site-rewards} by the site id from
 * {@code sites.yml} (as the file says), older configs by the service name the site sends; both work, the
 * service name is tried first. Keys are stored lower case.
 *
 * @author JExcellence
 * @since 3.3.0
 */
public final class SiteRewardLookup {

    private SiteRewardLookup() {
    }

    /**
     * @param bySite      the site-rewards map with lower-case keys
     * @param serviceName the service name the vote arrived with
     * @param siteId      the id of the configured site with that service name, or {@code null} when unknown
     * @param <T>         reward type
     * @return the extra rewards for that vote, empty when none are configured
     */
    public static <T> @NotNull List<T> find(@NotNull Map<String, List<T>> bySite, @NotNull String serviceName,
                                            @Nullable String siteId) {
        List<T> byService = bySite.get(serviceName.toLowerCase(Locale.ROOT));
        if (byService != null) {
            return byService;
        }
        if (siteId == null) {
            return List.of();
        }
        return bySite.getOrDefault(siteId.toLowerCase(Locale.ROOT), List.of());
    }
}
