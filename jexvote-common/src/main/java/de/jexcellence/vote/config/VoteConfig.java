package de.jexcellence.vote.config;

import de.jexcellence.vote.model.VoteSite;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    /**
     * Controls how streak milestone rewards are delivered.
     */
    public enum StreakClaimMode {
        /** Granted immediately when the milestone is reached (legacy). */
        AUTO,
        /** Player must claim from the streak GUI. */
        MANUAL
    }

    /**
     * Streak Freeze settings (Duolingo-style auto-equip protection).
     *
     * @param enabled       whether the feature is active
     * @param freeAmount    freezes granted for free on first profile creation
     * @param costPoints    vote points charged per purchased freeze
     * @param defaultMax    max freezes a player may own without a permission override
     * @param durationHours how much grace time a single freeze covers
     */
    public record FreezeSettings(boolean enabled, int freeAmount, int costPoints,
                                 int defaultMax, long durationHours) {}

    /**
     * Vote Gifting settings.
     *
     * @param enabled          whether gifting is active
     * @param dailyLimit       default gifts a player may send per day (perm-overridable)
     * @param requireVoteToday whether the gifter must have voted today to gift
     * @param timezone         day-boundary timezone for the daily gift limit
     */
    public record GiftSettings(boolean enabled, int dailyLimit,
                               boolean requireVoteToday, ZoneId timezone) {}

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
    private StreakClaimMode streakClaimMode = StreakClaimMode.AUTO;
    private int recordRetentionDays = 90;

    private List<String> commandsOnVote = Collections.emptyList();
    private Map<String, VoteSite> voteSites = Collections.emptyMap();
    private Map<Integer, List<String>> streakCommands = Collections.emptyMap();

    private boolean weekendMultiplierEnabled = false;
    private double weekendMultiplierFactor = 2.0;
    private Set<DayOfWeek> weekendMultiplierDays = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    private ZoneId weekendMultiplierTimezone = ZoneId.of("UTC");

    private boolean votePartyEnabled = false;
    private int votePartyTarget = 100;

    private FreezeSettings freezeSettings =
            new FreezeSettings(true, 1, 5, 3, 24L);
    private GiftSettings giftSettings =
            new GiftSettings(true, 1, true, ZoneId.of("UTC"));

    public VoteConfig(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void load() {
        YamlConfiguration config = ConfigMigrator.loadAndMigrate(plugin, CONFIG_FILE);

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

        String claimModeStr = config.getString("streak.claim-mode", "auto").trim().toUpperCase(Locale.ROOT);
        try {
            streakClaimMode = StreakClaimMode.valueOf(claimModeStr);
        } catch (IllegalArgumentException e) {
            logger.warning(String.format("Invalid streak.claim-mode '%s', using AUTO", claimModeStr));
            streakClaimMode = StreakClaimMode.AUTO;
        }

        recordRetentionDays = config.getInt("records.retention-days", 90);

        commandsOnVote = config.getStringList("commands-on-vote");

        loadMultipliers(config);
        loadVoteParty(config);
        loadStreakCommands(config);
        loadStreakFreeze(config);
        loadVoteGift(config);
        loadVoteSites();
    }

    private void loadStreakFreeze(@NotNull YamlConfiguration config) {
        boolean enabled = config.getBoolean("streak-freeze.enabled", true);
        int freeAmount = Math.max(0, config.getInt("streak-freeze.free-amount", 1));
        int costPoints = Math.max(0, config.getInt("streak-freeze.cost-points", 5));
        int defaultMax = Math.max(0, config.getInt("streak-freeze.default-max", 3));
        long durationHours = config.getLong("streak-freeze.duration-hours", 24L);
        if (durationHours < 1L) {
            logger.warning(String.format("Invalid streak-freeze.duration-hours %d — using 24", durationHours));
            durationHours = 24L;
        }
        freezeSettings = new FreezeSettings(enabled, freeAmount, costPoints, defaultMax, durationHours);
    }

    private void loadVoteGift(@NotNull YamlConfiguration config) {
        boolean enabled = config.getBoolean("vote-gift.enabled", true);
        int dailyLimit = Math.max(1, config.getInt("vote-gift.daily-limit", 1));
        boolean requireVoteToday = config.getBoolean("vote-gift.require-vote-today", true);

        String timezoneStr = config.getString("vote-gift.timezone", "UTC");
        ZoneId timezone;
        try {
            timezone = ZoneId.of(timezoneStr);
        } catch (Exception e) {
            logger.warning(String.format("Invalid vote-gift.timezone '%s', using UTC", timezoneStr));
            timezone = ZoneId.of("UTC");
        }
        giftSettings = new GiftSettings(enabled, dailyLimit, requireVoteToday, timezone);
    }

    private void loadMultipliers(@NotNull YamlConfiguration config) {
        weekendMultiplierEnabled = config.getBoolean("multipliers.weekend.enabled", false);
        weekendMultiplierFactor = config.getDouble("multipliers.weekend.factor", 2.0);

        List<String> dayNames = config.getStringList("multipliers.weekend.days");
        if (dayNames.isEmpty()) {
            weekendMultiplierDays = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        } else {
            weekendMultiplierDays = parseDays(dayNames);
        }

        String timezoneStr = config.getString("multipliers.weekend.timezone", "UTC");
        try {
            weekendMultiplierTimezone = ZoneId.of(timezoneStr);
        } catch (Exception e) {
            logger.warning(String.format("Invalid multipliers.weekend.timezone '%s', using UTC", timezoneStr));
            weekendMultiplierTimezone = ZoneId.of("UTC");
        }
    }

    private @NotNull Set<DayOfWeek> parseDays(@NotNull List<String> dayNames) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String name : dayNames) {
            try {
                days.add(DayOfWeek.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                logger.warning(String.format("Invalid weekend day '%s' in multipliers.weekend.days — ignored", name));
            }
        }
        if (days.isEmpty()) {
            return Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        }
        return Collections.unmodifiableSet(days);
    }

    private void loadVoteParty(@NotNull YamlConfiguration config) {
        votePartyEnabled = config.getBoolean("vote-party.enabled", false);
        votePartyTarget = config.getInt("vote-party.target", 100);
        if (votePartyTarget < 1) {
            logger.warning(String.format("Invalid vote-party.target %d — using 100", votePartyTarget));
            votePartyTarget = 100;
        }
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
        YamlConfiguration sitesConfig = ConfigMigrator.loadAndMigrate(plugin, "sites.yml");
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
    public @NotNull StreakClaimMode getStreakClaimMode() { return streakClaimMode; }
    public int getRecordRetentionDays() { return recordRetentionDays; }
    public @NotNull List<String> getCommandsOnVote() { return commandsOnVote; }
    public @NotNull Map<String, VoteSite> getVoteSites() { return voteSites; }
    public @NotNull Map<Integer, List<String>> getStreakCommands() { return streakCommands; }
    public boolean isWeekendMultiplierEnabled() { return weekendMultiplierEnabled; }
    public double getWeekendMultiplierFactor() { return weekendMultiplierFactor; }
    public @NotNull Set<DayOfWeek> getWeekendMultiplierDays() { return weekendMultiplierDays; }
    public @NotNull ZoneId getWeekendMultiplierTimezone() { return weekendMultiplierTimezone; }
    public boolean isVotePartyEnabled() { return votePartyEnabled; }
    public int getVotePartyTarget() { return votePartyTarget; }
    public @NotNull FreezeSettings getFreezeSettings() { return freezeSettings; }
    public @NotNull GiftSettings getGiftSettings() { return giftSettings; }
}
