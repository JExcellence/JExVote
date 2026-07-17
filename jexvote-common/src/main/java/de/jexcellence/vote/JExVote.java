package de.jexcellence.vote;

import com.raindropcentral.commands.CommandFactory;
import com.raindropcentral.commands.v2.argument.ArgumentType;
import com.raindropcentral.commands.v2.argument.ArgumentTypeRegistry;
import de.jexcellence.jehibernate.core.JEHibernate;
import de.jexcellence.jexplatform.JExPlatform;
import de.jexcellence.jexplatform.logging.LogLevel;
import de.jexcellence.jexplatform.reward.RewardRegistry;
import de.jexcellence.jexplatform.reward.RewardType;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.api.JExVoteAPI;
import de.jexcellence.vote.command.R18nCommandMessages;
import de.jexcellence.vote.command.VoteAdminHandler;
import de.jexcellence.vote.command.VoteCommandHandler;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.config.VotePartyConfig;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.rest.VoteRestApiServer;
import de.jexcellence.vote.reward.ChanceReward;
import de.jexcellence.vote.reward.LuckyReward;
import de.jexcellence.jexplatform.reward.impl.CurrencyReward;
import de.jexcellence.vote.reward.RewardStats;
import de.jexcellence.vote.service.RewardEconomy;
import de.jexcellence.vote.database.repository.ClaimedStreakRewardRepository;
import de.jexcellence.vote.database.repository.PendingVoteRewardRepository;
import de.jexcellence.vote.database.repository.RewardGrantStatRepository;
import de.jexcellence.vote.database.repository.VotePartyContributorRepository;
import de.jexcellence.vote.database.repository.VotePartyRepository;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import de.jexcellence.vote.database.repository.VoteRecordRepository;
import de.jexcellence.vote.listener.PlayerJoinListener;
import de.jexcellence.vote.placeholder.VotePlaceholderExpansion;
import de.jexcellence.vote.server.VotifierKeyManager;
import de.jexcellence.vote.server.VotifierServer;
import de.jexcellence.vote.service.MultiplierService;
import de.jexcellence.vote.service.RewardStatsService;
import de.jexcellence.vote.service.VotePartyService;
import de.jexcellence.vote.service.StreakClaimService;
import de.jexcellence.vote.service.StreakFreezeService;
import de.jexcellence.vote.service.VoteBroadcastService;
import de.jexcellence.vote.service.VoteGiftService;
import de.jexcellence.vote.service.VoteLeaderboardService;
import de.jexcellence.vote.service.VoteRewardService;
import de.jexcellence.vote.service.VoteService;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.vote.view.VoteLeaderboardView;
import de.jexcellence.vote.service.VoteShopService;
import de.jexcellence.vote.view.VoteOverviewView;
import de.jexcellence.vote.view.VoteLuckyView;
import de.jexcellence.vote.view.VotePartyView;
import de.jexcellence.vote.view.VoteRewardsView;
import de.jexcellence.vote.view.VoteShopView;
import de.jexcellence.vote.view.VoteStreakView;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Abstract base class for JExVote plugin initialization and lifecycle management.
 * Handles database setup, service initialization, votifier server, commands, and API registration.
 */
public abstract class JExVote {

    private final JavaPlugin plugin;
    private final String edition;
    private final Logger logger;

    private JExPlatform platform;
    private JEHibernate jeHibernate;

    private VoteConfig voteConfig;
    private VoteRewardConfig rewardConfig;
    private VotePartyConfig partyConfig;

    private VotePlayerRepository playerRepository;
    private VoteRecordRepository recordRepository;
    private PendingVoteRewardRepository pendingRewardRepository;
    private ClaimedStreakRewardRepository claimedStreakRepository;
    private VotePartyRepository partyRepository;
    private VotePartyContributorRepository partyContributorRepository;
    private RewardGrantStatRepository rewardStatRepository;

    private VoteService voteService;
    private VoteRewardService rewardService;
    private VoteLeaderboardService leaderboardService;
    private StreakClaimService streakClaimService;
    private StreakFreezeService streakFreezeService;
    private VoteGiftService voteGiftService;
    private VotePartyService votePartyService;
    private RewardStatsService rewardStatsService;
    private MultiplierService multiplierService;

    private VotifierServer votifierServer;
    private VoteRestApiServer restApiServer;
    private VotePlaceholderExpansion placeholders;
    private VoteProviderImpl voteProvider;
    private VoteOverviewView overviewView;
    private VoteLeaderboardView leaderboardView;
    private VoteRewardsView rewardsView;
    private VoteShopView shopView;

    /**
     * Creates a new JExVote instance.
     *
     * @param plugin  the JavaPlugin instance
     * @param edition the edition name (e.g., "Free", "Premium")
     */
    protected JExVote(@NotNull JavaPlugin plugin, @NotNull String edition) {
        this.plugin = plugin;
        this.edition = edition;
        this.logger = plugin.getLogger();
    }

    /**
     * Returns the metrics ID for bStats.
     *
     * @return the metrics ID
     */
    protected abstract int metricsId();

    /**
     * Returns the vote edition.
     *
     * @return the vote edition
     */
    protected abstract VoteEdition edition();

    public void onLoad() {
        logger.info("Loading JExVote " + edition + " Edition v" + plugin.getDescription().getVersion());

        voteConfig = new VoteConfig(plugin);
        voteConfig.load();
    }

    public void onEnable() {
        try {
            platform = JExPlatform.builder(plugin)
                    .withLogLevel(LogLevel.INFO)
                    .enableTranslations("en_US", "de_DE", "cs_CZ", "sk_SK")
                    .enableMetrics(metricsId())
                    .enableRewards()
                    .build();

            platform.initialize();
            initializeDatabase();
            initializeRepositories();
            initializeServices();
            initializeVotifierServer();
            registerListeners();
            registerViews();
            registerCommands();
            registerPlaceholders();
            registerApiProvider();
            initializeRestApiServer();

            logger.log(Level.INFO, () -> String.format("JExVote %s enabled — Votifier on port %d", edition, voteConfig.getServerPort()));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to enable JExVote", e);
            Bukkit.getPluginManager().disablePlugin(plugin);
        }
    }

    public void onDisable() {
        RewardStats.reset();
        CurrencyReward.clearDepositor();
        if (restApiServer != null) {
            restApiServer.stop();
        }
        if (votifierServer != null) {
            votifierServer.shutdown();
        }
        if (placeholders != null) {
            try { placeholders.unregister(); } catch (Throwable ignored) { /* Best-effort unregistration */ }
        }
        if (voteProvider != null) {
            try {
                Bukkit.getServicesManager().unregister(JExVoteAPI.class, voteProvider);
            } catch (Throwable ignored) { /* Best-effort unregistration */ }
        }
        if (jeHibernate != null) {
            jeHibernate.close();
        }
        if (platform != null) {
            platform.shutdown();
        }
        logger.info("JExVote disabled");
    }

    /**
     * Starts the embedded REST API (consumed by the Mythblock web backend)
     * if enabled in config. No-op when {@code api.enabled} is false.
     */
    private void initializeRestApiServer() {
        restApiServer = new VoteRestApiServer(
                voteConfig.getRestApiConfig(),
                voteConfig,
                playerRepository,
                recordRepository,
                logger);
        restApiServer.start();
    }

    private void initializeDatabase() {
        saveDefaultResource("database/hibernate.properties");

        jeHibernate = JEHibernate.builder()
                .configuration(config -> config.fromProperties(
                        de.jexcellence.jehibernate.config.PropertyLoader.load(
                                plugin.getDataFolder(), "database", "hibernate.properties")))
                .scanPackages("de.jexcellence.vote.database")
                .build();
    }

    private void saveDefaultResource(@NotNull String resourcePath) {
        var target = new File(plugin.getDataFolder(),
                resourcePath.replace('/', File.separatorChar));
        if (!target.exists()) {
            target.getParentFile().mkdirs();
            plugin.saveResource(resourcePath, false);
        }
    }

    private void persistTokenToConfig(@NotNull String token) {
        try {
            File configFile = new File(plugin.getDataFolder(), "config.yml");
            var config = new YamlConfiguration();
            config.options().parseComments(true);
            config.load(configFile);
            config.set("votifier.token", token);
            config.save(configFile);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not save generated token to config.yml", e);
        }
    }

    private void initializeRepositories() {
        var repos = jeHibernate.repositories();
        playerRepository = repos.get(VotePlayerRepository.class);
        recordRepository = repos.get(VoteRecordRepository.class);
        pendingRewardRepository = repos.get(PendingVoteRewardRepository.class);
        claimedStreakRepository = repos.get(ClaimedStreakRewardRepository.class);
        partyRepository = repos.get(VotePartyRepository.class);
        partyContributorRepository = repos.get(VotePartyContributorRepository.class);
        rewardStatRepository = repos.get(RewardGrantStatRepository.class);
    }

    private void initializeServices() {
        RewardRegistry rewardRegistry = platform.rewardRegistry().orElseThrow(
                () -> new IllegalStateException("Reward registry not initialized"));

        // Register JExVote's custom reward types before the reward config builds
        // its Jackson mapper from the registry, so they deserialize from rewards.yml.
        rewardRegistry.register(RewardType.plugin(ChanceReward.TYPE_ID, "jexvote", ChanceReward.class));
        rewardRegistry.register(RewardType.plugin(LuckyReward.TYPE_ID, "jexvote", LuckyReward.class));

        // Track how often keyed chance/lucky rewards are granted.
        rewardStatsService = new RewardStatsService(rewardStatRepository, logger);
        rewardStatsService.loadAsync();
        RewardStats.setRecorder(rewardStatsService::trackGrant);

        // Make 'currency' rewards actually pay out — JExPlatform's CurrencyReward
        // has no economy on its classpath, so install a depositor (JExEconomy → Vault).
        // This also covers currency nested inside chance/lucky rewards.
        CurrencyReward.setDepositor(new RewardEconomy(logger)::deposit);

        rewardConfig = new VoteRewardConfig(plugin, rewardRegistry);
        rewardConfig.load();

        partyConfig = new VotePartyConfig(plugin);
        partyConfig.load();

        multiplierService = new MultiplierService(
                edition().weekendMultiplierEnabled(),
                new MultiplierService.Settings(
                        voteConfig.isWeekendMultiplierEnabled(),
                        voteConfig.getWeekendMultiplierFactor(),
                        voteConfig.getWeekendMultiplierDays(),
                        voteConfig.getWeekendMultiplierTimezone()));

        rewardService = new VoteRewardService(
                logger, rewardRegistry,
                rewardConfig.getDefaultRewards(),
                rewardConfig.getGuaranteedRewards(),
                rewardConfig.getStreakRewards(),
                rewardConfig.getSiteRewards(),
                voteConfig.getCommandsOnVote(),
                multiplierService);

        boolean manualClaim = voteConfig.getStreakClaimMode() == VoteConfig.StreakClaimMode.MANUAL;
        rewardService.setManualStreakClaim(manualClaim);
        rewardService.setStreaksEnabled(voteConfig.isFeatureStreaks());

        streakClaimService = new StreakClaimService(
                plugin, claimedStreakRepository, playerRepository, rewardService);

        if (manualClaim) {
            streakClaimService.runMigration();
        }

        VoteBroadcastService broadcastService = new VoteBroadcastService(voteConfig);
        leaderboardService = new VoteLeaderboardService(playerRepository);

        // Enforce edition site limit
        Map<String, VoteSite> sites = voteConfig.getVoteSites();
        int maxSites = edition().maxVoteSites();
        if (maxSites > 0 && sites.size() > maxSites) {
            int configuredCount = sites.size();
            logger.log(Level.WARNING, () -> String.format("Free edition supports up to %d vote sites, but %d are configured. Only the first %d will be loaded.", maxSites, configuredCount, maxSites));
            var limited = new LinkedHashMap<String, VoteSite>();
            sites.entrySet().stream()
                    .limit(maxSites)
                    .forEach(entry -> limited.put(entry.getKey(), entry.getValue()));
            sites = Collections.unmodifiableMap(limited);
        }

        votePartyService = createVotePartyService(broadcastService);

        voteService = new VoteService(
                plugin, playerRepository, recordRepository,
                pendingRewardRepository, rewardService, broadcastService,
                multiplierService, votePartyService,
                sites, voteConfig.getStreakTimeoutHours(),
                voteConfig.getStreakCommands(),
                voteConfig.getRecordRetentionDays(),
                voteConfig.getFreezeSettings(),
                voteConfig.getBedrockSettings());

        streakFreezeService = new StreakFreezeService(playerRepository, voteConfig);
        voteGiftService = new VoteGiftService(playerRepository, voteConfig);

        // Purge old vote records on startup
        voteService.purgeOldRecords();
        // One-time, idempotent free Streak Freeze back-fill for existing players
        voteService.initializeFreezesForExistingPlayers();
    }

    private @Nullable VotePartyService createVotePartyService(@NotNull VoteBroadcastService broadcastService) {
        if (!edition().votePartyEnabled() || !voteConfig.isVotePartyEnabled()) {
            return null;
        }
        VotePartyService service = new VotePartyService(
                plugin, partyRepository, partyContributorRepository,
                pendingRewardRepository, rewardService, broadcastService,
                partyConfig,
                rewardConfig.getVotePartyRewards(), voteConfig.getVotePartyTarget());
        service.setPartyPool(rewardConfig.getVotePartyPool());
        return service;
    }

    private void initializeVotifierServer() {
        try {
            KeyPair keyPair = VotifierKeyManager.loadOrGenerate(
                    plugin.getDataFolder().toPath(), logger);

            String token = voteConfig.getServerToken();
            if (token.isEmpty()) {
                token = VotifierKeyManager.generateToken();
                persistTokenToConfig(token);
                logger.info("Generated and saved Votifier token to config.yml.");
                String savedToken = token;
                logger.log(Level.INFO, () -> String.format("Token: %s", savedToken));
                logger.log(Level.INFO, () -> String.format("Public key: %s", VotifierKeyManager.encodePublicKey(keyPair.getPublic())));
            }

            votifierServer = new VotifierServer(
                    logger,
                    voteConfig.getServerHost(),
                    voteConfig.getServerPort(),
                    keyPair,
                    token,
                    result -> {
                        logger.info("Vote received via " + result.protocol()
                                + ": " + result.vote().username()
                                + " on " + result.vote().serviceName());
                        voteService.processVote(result.vote()).whenComplete((success, error) -> {
                            if (error != null) {
                                logger.log(Level.SEVERE, error, () -> String.format("Error processing vote for %s", result.vote().username()));
                                return;
                            }
                            if (Boolean.TRUE.equals(success)) {
                                PlatformScheduler.of(plugin).runSync(() -> {
                                    var voter = Bukkit.getPlayerExact(result.vote().username());
                                    voteService.getBroadcastService().broadcastVote(
                                            result.vote().username(),
                                            result.vote().serviceName(),
                                            voter != null ? voter.getUniqueId() : null);
                                });
                            } else {
                                logger.warning("Vote processing returned false for "
                                        + result.vote().username()
                                        + " on " + result.vote().serviceName());
                            }
                        });
                    });

            votifierServer.start();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to start Votifier server", e);
        }
    }

    private void registerListeners() {
        var pm = Bukkit.getPluginManager();
        pm.registerEvents(new PlayerJoinListener(voteService), plugin);
    }

    private void registerCommands() {
        var factory = new CommandFactory(plugin, this);
        var registry = ArgumentTypeRegistry.defaults();
        var messages = new R18nCommandMessages();

        // Custom argument type: tab-completes configured vote site service names
        registry.register(ArgumentType.custom("vote_service", String.class,
                (sender, raw) -> ArgumentType.ParseResult.ok(raw),
                (sender, partial) -> {
                    var lower = partial.toLowerCase(Locale.ROOT);
                    return voteService.getVoteSites().values().stream()
                            .map(VoteSite::serviceName)
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
                            .toList();
                }));

        // Custom argument type: tab-completes online player names plus the
        // literal "random" for /vote gift.
        registry.register(ArgumentType.custom("vote_gift_target", String.class,
                (sender, raw) -> ArgumentType.ParseResult.ok(raw),
                (sender, partial) -> {
                    var lower = partial.toLowerCase(Locale.ROOT);
                    List<String> suggestions = new ArrayList<>();
                    if ("random".startsWith(lower)) {
                        suggestions.add("random");
                    }
                    Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
                            .forEach(suggestions::add);
                    return suggestions;
                }));

        // Save command YAMLs to data folder on first run so users can edit
        // names, aliases, permissions, and descriptions.
        saveDefaultResource("commands/vote.yml");
        saveDefaultResource("commands/jexvote.yml");

        var voteCommandHandler = new VoteCommandHandler(voteService, leaderboardService, voteConfig, overviewView,
                rewardsView, leaderboardView, streakFreezeService, voteGiftService);
        voteCommandHandler.setShopView(shopView);
        factory.registerTree(new File(plugin.getDataFolder(), "commands/vote.yml"),
                voteCommandHandler.handlerMap(),
                messages, registry);
        factory.registerTree(new File(plugin.getDataFolder(), "commands/jexvote.yml"),
                new VoteAdminHandler(plugin, edition(), voteService, voteConfig, rewardConfig).handlerMap(),
                messages, registry);

        factory.registerAllCommandsAndListeners();
        logger.info("Registered 2 command trees: /vote, /jexvote");
    }

    private void registerViews() {
        var pm = Bukkit.getPluginManager();

        overviewView = new VoteOverviewView(plugin, voteService, voteConfig);
        leaderboardView = new VoteLeaderboardView(plugin, leaderboardService);
        var streakView = new VoteStreakView(plugin, voteService, rewardService, streakClaimService);
        rewardsView = new VoteRewardsView(plugin, voteConfig, rewardConfig,
                multiplierService, votePartyService, rewardStatsService,
                streakFreezeService, voteGiftService);
        var partyView = new VotePartyView(rewardConfig, votePartyService, rewardStatsService);
        var shopService = new VoteShopService(plugin, playerRepository, rewardService, rewardConfig);
        shopView = new VoteShopView(plugin, shopService);
        var luckyView = new VoteLuckyView(rewardConfig, rewardStatsService);

        // Wire cross-navigation references
        overviewView.setLeaderboardView(leaderboardView);
        overviewView.setStreakView(streakView);
        overviewView.setRewardsView(rewardsView);
        leaderboardView.setOverviewView(overviewView);
        streakView.setOverviewView(overviewView);
        rewardsView.setOverviewView(overviewView);
        rewardsView.setPartyView(partyView);
        rewardsView.setShopView(shopView);
        rewardsView.setLuckyView(luckyView);
        overviewView.setShopView(shopView);
        partyView.setRewardsView(rewardsView);
        shopView.setRewardsView(rewardsView);
        luckyView.setRewardsView(rewardsView);

        // Register as Bukkit listeners (raw inventory click handling)
        pm.registerEvents(overviewView, plugin);
        pm.registerEvents(leaderboardView, plugin);
        pm.registerEvents(streakView, plugin);
        pm.registerEvents(rewardsView, plugin);
        pm.registerEvents(partyView, plugin);
        pm.registerEvents(shopView, plugin);
        pm.registerEvents(luckyView, plugin);
    }

    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholders = new VotePlaceholderExpansion(playerRepository, votePartyService, rewardStatsService);
            placeholders.register();
            logger.info("Registered PlaceholderAPI expansion: %jexvote_<placeholder>%");
        }
    }

    private void registerApiProvider() {
        voteProvider = new VoteProviderImpl(voteService, leaderboardService);
        JExVoteAPIImpl apiImpl = new JExVoteAPIImpl(voteProvider);
        Bukkit.getServicesManager().register(
                JExVoteAPI.class, apiImpl, plugin, ServicePriority.Normal);
    }

    /**
     * Returns the JavaPlugin instance.
     *
     * @return the plugin instance
     */
    public @NotNull JavaPlugin getPlugin() { return plugin; }

    /**
     * Returns the vote service.
     *
     * @return the vote service
     */
    public @NotNull VoteService getVoteService() { return voteService; }

    /**
     * Returns the leaderboard service.
     *
     * @return the leaderboard service
     */
    public @NotNull VoteLeaderboardService getLeaderboardService() { return leaderboardService; }

    /**
     * Returns the vote configuration.
     *
     * @return the vote config
     */
    public @NotNull VoteConfig getVoteConfig() { return voteConfig; }

    /**
     * Returns the overview view.
     *
     * @return the overview view
     */
    public @NotNull VoteOverviewView getOverviewView() { return overviewView; }
}
