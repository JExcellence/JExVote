package de.jexcellence.vote.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.reward.RewardRegistry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class VoteRewardConfig {

    private static final String REWARDS_FILE = "rewards.yml";

    private final JavaPlugin plugin;
    private final Logger logger;
    private final RewardRegistry rewardRegistry;
    private final ObjectMapper rewardMapper;

    private List<AbstractReward> defaultRewards = Collections.emptyList();
    private Map<Integer, List<AbstractReward>> streakRewards = Collections.emptyMap();
    private Map<String, List<AbstractReward>> siteRewards = Collections.emptyMap();

    public VoteRewardConfig(@NotNull JavaPlugin plugin, @NotNull RewardRegistry rewardRegistry) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.rewardRegistry = rewardRegistry;
        this.rewardMapper = buildRewardMapper(rewardRegistry);
    }

    private static @NotNull ObjectMapper buildRewardMapper(@NotNull RewardRegistry registry) {
        var mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        registry.types().forEach((name, type) ->
                mapper.registerSubtypes(new NamedType(type.implementationClass(), type.id())));
        return mapper;
    }

    public void load() {
        File rewardsFile = new File(plugin.getDataFolder(), REWARDS_FILE);
        if (!rewardsFile.exists()) {
            plugin.saveResource(REWARDS_FILE, false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(rewardsFile);

        var defaults = plugin.getResource(REWARDS_FILE);
        if (defaults != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }

        defaultRewards = loadRewardList(config.getConfigurationSection("default-rewards"));
        streakRewards = loadStreakRewards(config.getConfigurationSection("streak-rewards"));
        siteRewards = loadSiteRewards(config.getConfigurationSection("site-rewards"));

        logger.log(Level.INFO, () -> String.format("Loaded %d default reward(s), %d streak tier(s), %d site-specific reward set(s)",
                defaultRewards.size(), streakRewards.size(), siteRewards.size()));
    }

    private @NotNull List<AbstractReward> loadRewardList(ConfigurationSection section) {
        if (section == null) return Collections.emptyList();

        List<AbstractReward> rewards = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection rewardSection = section.getConfigurationSection(key);
            if (rewardSection == null) continue;

            try {
                String type = rewardSection.getString("type");
                if (type == null) {
                    logger.log(Level.WARNING, () -> String.format("Reward '%s' missing 'type' field", key));
                } else if (rewardRegistry.find(type).isEmpty()) {
                    logger.log(Level.WARNING, () -> String.format("Unknown reward type: %s", type));
                } else {
                    Map<String, Object> data = new LinkedHashMap<>(rewardSection.getValues(false));
                    String json = rewardMapper.writeValueAsString(data);
                    AbstractReward reward = rewardMapper.readValue(json, AbstractReward.class);
                    rewards.add(reward);
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, String.format("Failed to load reward '%s'", key), e);
            }
        }
        return Collections.unmodifiableList(rewards);
    }

    private @NotNull Map<Integer, List<AbstractReward>> loadStreakRewards(ConfigurationSection section) {
        if (section == null) return Collections.emptyMap();

        Map<Integer, List<AbstractReward>> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            try {
                int day = Integer.parseInt(key);
                ConfigurationSection daySection = section.getConfigurationSection(key);
                if (daySection != null) {
                    List<AbstractReward> rewards = loadRewardList(daySection);
                    if (!rewards.isEmpty()) {
                        map.put(day, rewards);
                    }
                }
            } catch (NumberFormatException e) {
                logger.warning(String.format("Invalid streak day: %s", key));
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private @NotNull Map<String, List<AbstractReward>> loadSiteRewards(ConfigurationSection section) {
        if (section == null) return Collections.emptyMap();

        Map<String, List<AbstractReward>> map = new LinkedHashMap<>();
        for (String siteId : section.getKeys(false)) {
            ConfigurationSection siteSection = section.getConfigurationSection(siteId);
            if (siteSection != null) {
                List<AbstractReward> rewards = loadRewardList(siteSection);
                if (!rewards.isEmpty()) {
                    map.put(siteId.toLowerCase(), rewards);
                }
            }
        }
        return Collections.unmodifiableMap(map);
    }

    public @NotNull List<AbstractReward> getDefaultRewards() { return defaultRewards; }
    public @NotNull Map<Integer, List<AbstractReward>> getStreakRewards() { return streakRewards; }
    public @NotNull Map<String, List<AbstractReward>> getSiteRewards() { return siteRewards; }
}
