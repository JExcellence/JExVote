package de.jexcellence.vote.integration;

import org.jetbrains.annotations.Nullable;

/**
 * The JExOneblock profile that receives the vote rewards of a Season profile: the player's Normal profile with the
 * lowest slot. Free of JExOneblock types so {@link IronmanGate} loads without the plugin.
 *
 * @param id       profile id
 * @param slot     1-based profile slot
 * @param label    optional player-chosen label
 * @param islandId the profile's island id, or {@code null} before its island exists
 * @author JExcellence
 * @since 3.2.10
 */
public record RewardProfile(long id, int slot, @Nullable String label, @Nullable Long islandId) {
}
