package de.jexcellence.vote.service;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A small set of text lines kept in a file in the data folder, so a guard survives a restart. Every change is written
 * to a temporary file first and moved into place, so a crash never leaves a half-written file.
 *
 * @author JExcellence
 * @since 3.2.11
 */
final class LineSetFile {

    private final File file;
    private final Logger logger;
    private final Set<String> lines = new TreeSet<>();

    /**
     * Loads the file; lines {@code valid} rejects are skipped with a warning.
     *
     * @param dataFolder the plugin data folder
     * @param fileName   the file name inside it
     * @param valid      whether a line is well-formed
     */
    LineSetFile(@NotNull File dataFolder, @NotNull String fileName, @NotNull Logger logger,
                @NotNull Predicate<String> valid) {
        this.file = new File(dataFolder, fileName);
        this.logger = logger;
        load(valid);
    }

    synchronized boolean contains(@NotNull String line) {
        return lines.contains(line);
    }

    synchronized void add(@NotNull String line) {
        if (lines.add(line)) {
            persist();
        }
    }

    synchronized void remove(@NotNull String line) {
        if (lines.remove(line)) {
            persist();
        }
    }

    synchronized @NotNull List<String> all() {
        return List.copyOf(lines);
    }

    private void load(@NotNull Predicate<String> valid) {
        if (!file.isFile()) {
            return;
        }
        try {
            for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (valid.test(line)) {
                    lines.add(line);
                } else {
                    logger.log(Level.WARNING, () -> "Skipping a malformed line in " + file.getName() + ": " + line);
                }
            }
        } catch (IOException ex) {
            logger.log(Level.SEVERE, ex, () -> "Could not load " + file.getName());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Path staged = file.toPath().resolveSibling(file.getName() + ".tmp");
            Files.write(staged, lines, StandardCharsets.UTF_8);
            Files.move(staged, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            logger.log(Level.SEVERE, ex, () -> "Could not save " + file.getName());
        }
    }
}
