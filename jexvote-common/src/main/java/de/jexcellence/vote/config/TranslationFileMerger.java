package de.jexcellence.vote.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Translation files are copied into the data folder once, so an update never reaches them and new menu texts show
 * as raw keys. Before the translation system loads, this writes every key the bundled file has and the server copy
 * lacks into the server copy. Existing texts, including an owner's own wording, are never changed; a timestamped
 * backup is written before a file changes.
 */
public final class TranslationFileMerger {

    private static final String DIRECTORY = "translations";

    private TranslationFileMerger() {
    }

    /** Adds the missing bundled keys to each existing {@code translations/<locale>.yml}. */
    public static void addMissingKeys(@NotNull JavaPlugin plugin, @NotNull List<String> locales) {
        for (String locale : locales) {
            mergeLocale(plugin, locale);
        }
    }

    private static void mergeLocale(@NotNull JavaPlugin plugin, @NotNull String locale) {
        String resourcePath = DIRECTORY + "/" + locale + ".yml";
        File live = new File(new File(plugin.getDataFolder(), DIRECTORY), locale + ".yml");
        if (!live.isFile()) {
            return;
        }
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                return;
            }
            YamlConfiguration bundled = load(in);
            YamlConfiguration current = YamlConfiguration.loadConfiguration(live);
            List<String> added = missingLeaves(bundled, current);
            if (added.isEmpty()) {
                return;
            }
            for (String key : added) {
                current.set(key, bundled.get(key));
            }
            File backup = new File(live.getPath() + ".bak-" + System.currentTimeMillis());
            Files.copy(live.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            current.save(live);
            final int count = added.size();
            plugin.getLogger().log(Level.INFO, () -> "Added " + count + " new translation keys to " + resourcePath
                    + " (backup: " + backup.getName() + ")");
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, ex, () -> "Could not update " + resourcePath);
        }
    }

    private static @NotNull YamlConfiguration load(@NotNull InputStream in) throws IOException {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    /** Leaf keys of {@code bundled} that {@code current} has no value for; package-visible for tests. */
    static @NotNull List<String> missingLeaves(@NotNull YamlConfiguration bundled, @NotNull YamlConfiguration current) {
        List<String> missing = new ArrayList<>();
        for (String key : bundled.getKeys(true)) {
            if (!bundled.isConfigurationSection(key) && !current.contains(key)) {
                missing.add(key);
            }
        }
        return missing;
    }
}
