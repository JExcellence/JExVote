package de.jexcellence.vote.service;

import de.jexcellence.vote.model.Vote;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent queue of votes whose voter could not be resolved to a UUID at the
 * time the vote arrived (a name that has never joined this server, or is missing
 * from the offline-player cache). Previously such a vote was dropped and lost;
 * here it is stored and replayed on the voter's first join, so neither the reward
 * nor the streak is lost - closing the "server up, player off, vote didn't count"
 * gap.
 *
 * <p>Backed by a flat file ({@code unresolved-votes.txt}), one vote per line as
 * {@code username|serviceName|address|epochMillis}. Access is synchronized and the
 * file is rewritten on every mutation - the queue is expected to stay small (only
 * never-resolved voters accumulate).
 *
 * @author JExcellence
 */
public final class UnresolvedVoteStore {

    private static final String SEP = "|";

    private final File file;
    private final Logger logger;
    private final List<Vote> votes = new ArrayList<>();

    public UnresolvedVoteStore(@NotNull File dataFolder, @NotNull Logger logger) {
        this.file = new File(dataFolder, "unresolved-votes.txt");
        this.logger = logger;
        load();
    }

    /** Queues a vote whose voter is not resolvable yet. */
    public synchronized void add(@NotNull Vote vote) {
        votes.add(vote);
        persist();
    }

    /**
     * Removes and returns every queued vote matching {@code matcher} (e.g. votes
     * whose username now resolves to a joining player).
     */
    public synchronized @NotNull List<Vote> drain(@NotNull Predicate<Vote> matcher) {
        List<Vote> matched = new ArrayList<>();
        boolean changed = votes.removeIf(vote -> {
            if (matcher.test(vote)) {
                matched.add(vote);
                return true;
            }
            return false;
        });
        if (changed) {
            persist();
        }
        return matched;
    }

    /** Number of queued votes (for diagnostics). */
    public synchronized int size() {
        return votes.size();
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                parseLine(line);
            }
        } catch (IOException ex) {
            logger.log(Level.WARNING, ex, () -> "Could not load unresolved votes");
        }
    }

    private void parseLine(@NotNull String line) {
        if (line.isBlank()) {
            return;
        }
        String[] parts = line.split("\\|", 4);
        if (parts.length != 4) {
            return;
        }
        try {
            votes.add(new Vote(parts[0], parts[1], parts[2],
                    Instant.ofEpochMilli(Long.parseLong(parts[3]))));
        } catch (NumberFormatException ignored) {
            // Skip a malformed persisted line rather than failing the whole restore.
        }
    }

    private void persist() {
        List<String> lines = new ArrayList<>(votes.size());
        for (Vote vote : votes) {
            lines.add(vote.username() + SEP + vote.serviceName() + SEP
                    + vote.address() + SEP + vote.timestamp().toEpochMilli());
        }
        try {
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            logger.log(Level.WARNING, ex, () -> "Could not persist unresolved votes");
        }
    }
}
