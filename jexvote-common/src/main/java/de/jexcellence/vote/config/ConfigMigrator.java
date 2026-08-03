package de.jexcellence.vote.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Merges newly added keys from a bundled default resource into the user's
 * on-disk configuration file, preserving existing values.
 *
 * <p>Comment round-tripping is enabled via {@code parseComments(true)}, so both
 * existing user comments and the header/inline comments attached to newly added
 * keys are retained on save (requires a server API that supports YAML comments,
 * i.e. Spigot/Paper 1.18.1+).
 */
public final class ConfigMigrator {

    private static final int DEFAULT_BOUNDARY_DEPTH = 1;
    private static final Map<String, Integer> FILE_BOUNDARY_DEPTHS = Map.of(
            "sites.yml", 2
    );

    private ConfigMigrator() {
        // Utility class — no instances
    }

    /**
     * Loads {@code fileName} from the plugin data folder, merges any keys present
     * in the bundled default but missing from the user's file, saves if changed,
     * and registers the bundled default as a runtime fallback.
     *
     * @param plugin   the owning plugin (source of the bundled default + data folder)
     * @param fileName the config file name (e.g. {@code "config.yml"})
     * @return the loaded (and possibly migrated) configuration
     */
    public static @NotNull YamlConfiguration loadAndMigrate(@NotNull JavaPlugin plugin,
                                                            @NotNull String fileName) {
        Logger logger = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
        }

        YamlConfiguration user = new YamlConfiguration();
        user.options().parseComments(true);
        try {
            user.load(file);
        } catch (Exception e) {
            logger.log(Level.WARNING, e, () -> String.format("Failed to load %s — using bundled defaults only", fileName));
        }

        YamlConfiguration defaults = loadBundledDefaults(plugin, fileName);
        if (defaults == null) {
            return user;
        }

        int added = mergeMissingKeys(user, defaults, boundaryDepth(fileName));
        if (added > 0) {
            saveMigrated(logger, file, user, fileName, added);
        }

        // Keep bundled defaults as a runtime fallback regardless of write-back.
        user.setDefaults(defaults);
        return user;
    }

    private static YamlConfiguration loadBundledDefaults(@NotNull JavaPlugin plugin,
                                                         @NotNull String fileName) {
        InputStream defaultsStream = plugin.getResource(fileName);
        if (defaultsStream == null) {
            return null;
        }

        YamlConfiguration defaults = new YamlConfiguration();
        defaults.options().parseComments(true);
        try {
            defaults.load(new InputStreamReader(defaultsStream, StandardCharsets.UTF_8));
            return defaults;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, e,
                    () -> String.format("Failed to load bundled default for %s", fileName));
            return null;
        }
    }

    /**
     * Copies every default key absent from {@code user}, but only under
     * configured ownership sections that already exist in the user's file. Most
     * files use their top-level section as the boundary. Dynamic registries such
     * as {@code sites.yml} use {@code sites.<siteId>}, allowing an operator to
     * delete one default entry while still receiving new fields for entries they
     * retained.
     *
     * @return the number of leaf values added
     */
    static int mergeMissingKeys(@NotNull YamlConfiguration user,
                                @NotNull YamlConfiguration defaults,
                                int boundaryDepth) {
        List<String> missing = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (!user.contains(key)) {
                String boundary = boundaryOf(key, boundaryDepth);
                if (user.contains(boundary)) {
                    missing.add(key);
                }
            }
        }

        for (String key : missing) {
            if (!defaults.isConfigurationSection(key)) {
                user.set(key, defaults.get(key));
            }
        }

        for (String key : missing) {
            copyComments(user, defaults, key);
        }

        return (int) missing.stream()
                .filter(key -> !defaults.isConfigurationSection(key))
                .count();
    }

    private static int boundaryDepth(@NotNull String fileName) {
        return FILE_BOUNDARY_DEPTHS.getOrDefault(
                fileName.toLowerCase(Locale.ROOT), DEFAULT_BOUNDARY_DEPTH);
    }

    private static @NotNull String boundaryOf(@NotNull String key, int requestedDepth) {
        int depth = Math.max(1, requestedDepth);
        int separator = -1;
        for (int segment = 1; segment < depth; segment++) {
            separator = key.indexOf('.', separator + 1);
            if (separator < 0) {
                return key;
            }
        }
        int boundaryEnd = key.indexOf('.', separator + 1);
        return boundaryEnd < 0 ? key : key.substring(0, boundaryEnd);
    }

    private static void copyComments(@NotNull YamlConfiguration user,
                                     @NotNull YamlConfiguration defaults,
                                     @NotNull String key) {
        List<String> header = defaults.getComments(key);
        if (!header.isEmpty()) {
            user.setComments(key, header);
        }
        List<String> inline = defaults.getInlineComments(key);
        if (!inline.isEmpty()) {
            user.setInlineComments(key, inline);
        }
    }

    private static void saveMigrated(@NotNull Logger logger, @NotNull File file,
                                     @NotNull YamlConfiguration user,
                                     @NotNull String fileName, int added) {
        try {
            user.save(file);
            logger.log(Level.INFO, () -> String.format(
                    "Migrated %s: added %d new key(s) from defaults", fileName, added));
        } catch (IOException e) {
            logger.log(Level.WARNING, e, () -> String.format("Failed to save migrated %s", fileName));
        }
    }
}
