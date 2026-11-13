package de.jexcellence.vote.service;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Streak claims that were paid out but whose claim record could not be saved. They are kept in a file in the data
 * folder, so the day stays closed across restarts until the record is saved, with its auto-claim flag.
 *
 * @author JExcellence
 * @since 3.2.11
 */
final class UnsavedClaimStore {

    private static final String FILE_NAME = "unsaved-streak-claims.txt";
    private static final String SEPARATOR = ":";
    private static final String AUTO_SUFFIX = SEPARATOR + "auto";

    private final LineSetFile lines;

    UnsavedClaimStore(@NotNull File dataFolder, @NotNull Logger logger) {
        this.lines = new LineSetFile(dataFolder, FILE_NAME, logger, UnsavedClaimStore::isValid);
    }

    /** One stored claim. */
    record UnsavedClaim(@NotNull UUID player, int day, boolean auto) {
    }

    boolean contains(@NotNull UUID player, int day) {
        String key = key(player, day);
        return lines.contains(key) || lines.contains(key + AUTO_SUFFIX);
    }

    void add(@NotNull UUID player, int day, boolean auto) {
        lines.add(auto ? key(player, day) + AUTO_SUFFIX : key(player, day));
    }

    void remove(@NotNull UUID player, int day) {
        String key = key(player, day);
        lines.remove(key);
        lines.remove(key + AUTO_SUFFIX);
    }

    @NotNull Optional<UnsavedClaim> find(@NotNull UUID player, int day) {
        return all().stream().filter(claim -> claim.player().equals(player) && claim.day() == day).findFirst();
    }

    @NotNull List<UnsavedClaim> all() {
        return lines.all().stream().map(UnsavedClaimStore::parse).toList();
    }

    /** The key of one player's milestone day, also used to guard a claim that is in progress. */
    static @NotNull String key(@NotNull UUID player, int day) {
        return player + SEPARATOR + day;
    }

    private static @NotNull UnsavedClaim parse(@NotNull String line) {
        boolean auto = line.endsWith(AUTO_SUFFIX);
        String key = auto ? line.substring(0, line.length() - AUTO_SUFFIX.length()) : line;
        int split = key.lastIndexOf(SEPARATOR);
        return new UnsavedClaim(UUID.fromString(key.substring(0, split)), Integer.parseInt(key.substring(split + 1)),
                auto);
    }

    private static boolean isValid(@NotNull String line) {
        try {
            parse(line);
            return true;
        } catch (IllegalArgumentException | StringIndexOutOfBoundsException ex) {
            return false;
        }
    }
}
