package de.jexcellence.vote.config;

import de.jexcellence.vote.model.VoteSite;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class VoteConfig {

    private static final String CONFIG_FILE = "config.yml";

    /**
     * Controls who receives vote broadcast messages.
     */
    public enum BroadcastMode {
        /** All online players including the voter. */
        ALL,
        /** All online players except the voter. */
        OTHERS,
        /** Broadcasts disabled. */
        NONE
    }

    private final JavaPlugin plugin;
    private final Logger logger;

    private String serverHost = "";
    private int serverPort = 8192;
    private String serverToken = "";

    private BroadcastMode broadcastMode = BroadcastMode.ALL;
    private int broadcastCooldownSeconds = 0;
    private boolean privateMessageEnabled = true;

    private boolean offlineVoteQueue = true;
    private int streakTimeoutHours = 36;
    private int recordRetentionDays = 90;

    private List<String> commandsOnVote = Collections.emptyList();
    private Map<String, VoteSite> voteSites = Collections.emptyMap();
    private Map<Integer, List<String>> streakCommands = Collections.emptyMap();

    public VoteConfig(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void load() {
        File configFile = new File(plugin.getDataFolder(), CONFIG_FILE);
        if (!configFile.exists()) {
            plugin.saveResource(CONFIG_FILE, false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        var defaults = plugin.getResource(CONFIG_FILE);
        if (defaults != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }

        ConfigurationSection server = config.getConfigurationSection("votifier");
        if (server != null) {
            serverHost = server.getString("host", "");
            serverPort = server.getInt("port", 8192);
            serverToken = server.getString("token", "");
        }

        // Broadcast settings
        String modeStr = config.getString("broadcast.mode", "all").trim().toUpperCase(Locale.ROOT);
        try {
            broadcastMode = BroadcastMode.valueOf(modeStr);
        } catch (IllegalArgumentException e) {
            logger.warning(String.format("Invalid broadcast.mode '%s', using ALL", modeStr));
            broadcastMode = BroadcastMode.ALL;
        }
        broadcastCooldownSeconds = config.getInt("broadcast.cooldown", 0);

        // Private message
        privateMessageEnabled = config.getBoolean("private-message.enabled", true);

        offlineVoteQueue = config.getBoolean("offline-vote-queue", true);
        streakTimeoutHours = config.getInt("streak.timeout-hours", 36);
        recordRetentionDays = config.getInt("records.retention-days", 90);

        commandsOnVote = config.getStringList("commands-on-vote");

        loadStreakCommands(config);
        loadVoteSites();
    }

    private void loadStreakCommands(@NotNull YamlConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("streak.bonus-commands");
        if (section == null) {
            streakCommands = Collections.emptyMap();
            return;
        }

        Map<Integer, List<String>> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            try {
                int day = Integer.parseInt(key);
                List<String> commands = section.getStringList(key);
                if (!commands.isEmpty()) {
                    map.put(day, commands);
                }
            } catch (NumberFormatException e) {
                logger.warning(String.format("Invalid streak day number in config: %s", key));
            }
        }
        streakCommands = Collections.unmodifiableMap(map);
    }

    private void loadVoteSites() {
        File sitesFile = new File(plugin.getDataFolder(), "sites.yml");
        if (!sitesFile.exists()) {
            plugin.saveResource("sites.yml", false);
        }

        YamlConfiguration sitesConfig = YamlConfiguration.loadConfiguration(sitesFile);
        ConfigurationSection section = sitesConfig.getConfigurationSection("sites");
        if (section == null) {
            voteSites = Collections.emptyMap();
            return;
        }

        Map<String, VoteSite> sites = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection siteSection = section.getConfigurationSection(id);
            if (siteSection == null) continue;

            String displayName = siteSection.getString("display-name", id);
            String serviceName = siteSection.getString("service-name", id);
            String voteUrl = siteSection.getString("vote-url", null);
            int points = siteSection.getInt("points-per-vote", 1);

            String resetTimeStr = siteSection.getString("daily-reset", null);
            String timezoneStr = siteSection.getString("timezone", "UTC");

            ZoneId timezone;
            try {
                timezone = ZoneId.of(timezoneStr);
            } catch (Exception e) {
                logger.warning(String.format("Invalid timezone '%s' for site %s, using UTC", timezoneStr, id));
                timezone = ZoneId.of("UTC");
            }

            LocalTime dailyResetTime = null;
            Duration cooldown = null;

            if (resetTimeStr != null) {
                try {
                    dailyResetTime = LocalTime.parse(resetTimeStr);
                } catch (DateTimeParseException e) {
                    logger.warning(String.format("Invalid daily-reset time '%s' for site %s, falling back to cooldown-minutes",
                            resetTimeStr, id));
                }
            }

            if (dailyResetTime == null) {
                int cooldownMinutes = siteSection.getInt("cooldown-minutes", 1440);
                cooldown = Duration.ofMinutes(cooldownMinutes);
            }

            sites.put(id, new VoteSite(id, displayName, serviceName, voteUrl,
                    cooldown, dailyResetTime, timezone, points));
        }
        voteSites = Collections.unmodifiableMap(sites);
        int siteCount = sites.size();
        logger.log(Level.INFO, () -> String.format("Loaded %d vote site(s)", siteCount));
    }

    public String getServerHost() { return serverHost; }
    public int getServerPort() { return serverPort; }
    public String getServerToken() { return serverToken; }
    public @NotNull BroadcastMode getBroadcastMode() { return broadcastMode; }
    public int getBroadcastCooldownSeconds() { return broadcastCooldownSeconds; }
    public boolean isPrivateMessageEnabled() { return privateMessageEnabled; }
    public boolean isOfflineVoteQueue() { return offlineVoteQueue; }
    public int getStreakTimeoutHours() { return streakTimeoutHours; }
    public int getRecordRetentionDays() { return recordRetentionDays; }
    public @NotNull List<String> getCommandsOnVote() { return commandsOnVote; }
    public @NotNull Map<String, VoteSite> getVoteSites() { return voteSites; }
    public @NotNull Map<Integer, List<String>> getStreakCommands() { return streakCommands; }
}
