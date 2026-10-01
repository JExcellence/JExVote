package de.jexcellence.vote;

import de.jexcellence.vote.config.TranslationFileMerger;
import de.jexcellence.vote.command.CommandTreeMerger;
import com.raindropcentral.commands.CommandFactory;
import com.raindropcentral.commands.v2.argument.ArgumentType;
import com.raindropcentral.commands.v2.argument.ArgumentTypeRegistry;
import de.jexcellence.jehibernate.core.JEHibernate;
import de.jexcellence.jexplatform.JExPlatform;
import de.jexcellence.jexplatform.command.migration.CommandFileMigration;
import de.jexcellence.jexplatform.logging.LogLevel;
import de.jexcellence.jexplatform.reward.RewardRegistry;
import de.jexcellence.jexplatform.reward.RewardType;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.api.JExVoteAPI;
import de.jexcellence.vote.command.AdminStatus;
import de.jexcellence.vote.command.R18nCommandMessages;
import de.jexcellence.vote.command.VoteAdminHandler;
import de.jexcellence.vote.command.VoteCommandHandler;
import de.jexcellence.vote.config.CurrencyDisplay;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.config.VoteEffectsConfig;
import de.jexcellence.vote.config.VoteFeatures;
import de.jexcellence.vote.config.VotePartyConfig;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.rest.VoteRestApiServer;
import de.jexcellence.vote.reward.ChanceReward;
import de.jexcellence.vote.bedrock.BedrockFormBridge;
import de.jexcellence.vote.bedrock.VoteBedrockForms;
import de.jexcellence.vote.bedrock.VoteSettingsForm;
import de.jexcellence.vote.reward.LuckyReward;
import de.jexcellence.vote.reward.RewardAnnouncer;
import de.jexcellence.jexplatform.reward.impl.CurrencyReward;
import de.jexcellence.vote.reward.RewardStats;
import de.jexcellence.vote.service.RewardEconomy;
import de.jexcellence.vote.database.repository.ClaimedStreakRewardRepository;
import de.jexcellence.vote.database.repository.PendingVoteRewardRepository;
import de.jexcellence.vote.database.repository.RewardGrantStatRepository;
import de.jexcellence.vote.database.repository.VotePartyContributorRepository;
import de.jexcellence.vote.database.repository.VotePartyRepository;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import de.jexcellence.vote.database.repository.VotePlayerSettingsRepository;
import de.jexcellence.vote.database.repository.VoteRecordRepository;
import de.jexcellence.vote.database.repository.VoteSyncEventRepository;
import de.jexcellence.vote.listener.PlayerJoinListener;
import de.jexcellence.vote.placeholder.VotePlaceholderExpansion;
import de.jexcellence.vote.server.VotifierKeyManager;
import de.jexcellence.vote.server.VotifierServer;
import de.jexcellence.vote.service.MultiplierService;
import de.jexcellence.vote.service.NormalProfileRewards;
import de.jexcellence.vote.service.RewardStatsService;
import de.jexcellence.vote.service.VotePartyService;
import de.jexcellence.vote.service.StreakClaimService;
import de.jexcellence.vote.service.StreakFreezeService;
import de.jexcellence.vote.service.VoteBroadcastService;
import de.jexcellence.vote.service.VoteGiftService;
import de.jexcellence.vote.service.VoteReconciliationService;
import de.jexcellence.vote.service.VoteLeaderboardService;
import de.jexcellence.vote.service.VoteRewardService;
import de.jexcellence.vote.service.VoteRewardProviderRegistry;
import de.jexcellence.vote.service.VoteDescriptorExecutor;
import de.jexcellence.vote.service.ProxyVoteSyncService;
import de.jexcellence.vote.service.OutboxProxyEventBus;
import de.jexcellence.vote.service.VoteService;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.vote.settings.DiscordReminderBridge;
import de.jexcellence.vote.settings.VoteReminderService;
import de.jexcellence.vote.settings.VoteSettingsService;
import de.jexcellence.vote.view.VoteBaseView;
import de.jexcellence.vote.view.VoteLeaderboardView;
import de.jexcellence.vote.view.VoteRewardDescriber;
import de.jexcellence.vote.service.VoteShopService;
import de.jexcellence.vote.view.VoteOverviewView;
import de.jexcellence.vote.view.VoteLuckyView;
import de.jexcellence.vote.view.VotePartyView;
import de.jexcellence.vote.view.VoteRewardsView;
import de.jexcellence.vote.view.VoteSettingsView;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Abstract base class for JExVote plugin initialization and lifecycle management.
 * Handles database setup, service initialization, votifier server, commands, and API registration.
 */
public abstract class JExVote {

    private static final String DEFAULT_LOCALE = "en_US";
    private static final String[] EXTRA_LOCALES = {"de_DE", "cs_CZ", "sk_SK"};

    private final JavaPlugin plugin;
    private final String edition;
    private final Logger logger;

    private JExPlatform platform;
    private JEHibernate jeHibernate;

    private VoteConfig voteConfig;
    private VoteRewardConfig rewardConfig;
    private VotePartyConfig partyConfig;
    private VoteEffectsConfig effectsConfig;

    private VotePlayerRepository playerRepository;
    private VoteRecordRepository recordRepository;
    private PendingVoteRewardRepository pendingRewardRepository;
    private ClaimedStreakRewardRepository claimedStreakRepository;
    private VotePartyRepository partyRepository;
    private VotePartyContributorRepository partyContributorRepository;
    private RewardGrantStatRepository rewardStatRepository;
    private VoteSyncEventRepository syncEventRepository;
    private VotePlayerSettingsRepository settingsRepository;

    private VoteService voteService;
    private VoteRewardService rewardService;
    private VoteLeaderboardService leaderboardService;
    private StreakClaimService streakClaimService;
    private StreakFreezeService streakFreezeService;
    private VoteGiftService voteGiftService;
    private VoteReconciliationService voteReconciliationService;
    private VotePartyService votePartyService;
    private RewardStatsService rewardStatsService;
    private MultiplierService multiplierService;
    private VoteRewardProviderRegistry rewardSpiRegistry;
    private VoteSettingsService settingsService;
    private VoteReminderService reminderService;
    private VoteSettingsView settingsView;

    private VotifierServer votifierServer;
    private VoteRestApiServer restApiServer;
    private VotePlaceholderExpansion placeholders;
    private VoteProviderImpl voteProvider;
    private VoteOverviewView overviewView;
    private VoteLeaderboardView leaderboardView;
    private VoteRewardsView rewardsView;
    private VoteShopView shopView;
    private VoteStreakView streakView;
    private VotePartyView partyView;
    private VoteShopService shopService;
    private VoteFeatures features;
    private boolean bedrockFormsHooked;

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

    private static @NotNull List<String> allLocales() {
        List<String> locales = new ArrayList<>(List.of(EXTRA_LOCALES));
        locales.add(0, DEFAULT_LOCALE);
        return locales;
    }

    public void onEnable() {
        CommandFileMigration.oneblockAdminPaths(plugin.getLogger()).runOnce(plugin.getDataFolder().toPath());
        try {
            TranslationFileMerger.addMissingKeys(plugin, allLocales());
            platform = JExPlatform.builder(plugin)
                    .withLogLevel(LogLevel.INFO)
                    .enableTranslations(DEFAULT_LOCALE, EXTRA_LOCALES)
                    .enableMetrics(metricsId())
                    .enableRewards()
                    .build();

            platform.initialize();
            features = new VoteFeatures(edition(), voteConfig);
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

            logger.log(Level.INFO, () -> String.format("JExVote %s enabled - Votifier on port %d", edition, voteConfig.getServerPort()));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to enable JExVote", e);
            Bukkit.getPluginManager().disablePlugin(plugin);
        }
    }

    public void onDisable() {
        RewardStats.reset();
        RewardAnnouncer.reset();
        CurrencyReward.clearDepositor();
        if (reminderService != null) {
            reminderService.stop();
        }
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
     * Starts the embedded REST API (for a server website)
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
        syncEventRepository = repos.get(VoteSyncEventRepository.class);
        settingsRepository = repos.get(VotePlayerSettingsRepository.class);
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

        // Make 'currency' rewards actually pay out - JExPlatform's CurrencyReward
        // has no economy on its classpath, so install a depositor (JExEconomy → Vault).
        // This also covers currency nested inside chance/lucky rewards. The same
        // instance backs the reward-SPI executor (Currency descriptors).
        RewardEconomy rewardEconomy = new RewardEconomy(logger);
        CurrencyReward.setDepositor(rewardEconomy::deposit);

        rewardConfig = new VoteRewardConfig(plugin, rewardRegistry);
        rewardConfig.load();
        applyCurrencyDisplay();

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
        effectsConfig = new VoteEffectsConfig(plugin);
        effectsConfig.load();
        broadcastService.setEffects(effectsConfig);
        settingsService = new VoteSettingsService(settingsRepository, voteConfig, features,
                new DiscordReminderBridge(logger), logger);
        broadcastService.setPreferences(settingsService);
        RewardAnnouncer.install(broadcastService::broadcastLuckyWin);
        leaderboardService = new VoteLeaderboardService(playerRepository);

        Map<String, VoteSite> sites = loadedSites();

        votePartyService = createVotePartyService(broadcastService);

        voteService = new VoteService(
                plugin, playerRepository, recordRepository,
                pendingRewardRepository, rewardService, broadcastService,
                multiplierService, votePartyService,
                sites, voteConfig.getStreakTimeoutHours(),
                voteConfig.getStreakCommands(),
                voteConfig.getRecordRetentionDays(),
                voteConfig.getFreezeSettings(),
                voteConfig.getBedrockSettings(),
                voteConfig.getDailyFlySettings(),
                voteConfig.getDailyRewardCommands());

        rewardService.setSiteIdResolver(service -> {
            VoteSite site = voteService.findSiteByServiceName(service);
            return site == null ? null : site.id();
        });

        // Reward SPI (V2): registry holds third-party providers (inert on Free), the
        // executor turns their platform-free descriptors into real grants. Wired into
        // VoteService post-construction (the executor needs its grantVotePoints sink).
        rewardSpiRegistry = new VoteRewardProviderRegistry(logger, edition().rewardSpiEnabled());
        VoteDescriptorExecutor descriptorExecutor =
                new VoteDescriptorExecutor(logger, rewardEconomy, voteService::grantVotePoints);
        voteService.setRewardSpi(rewardSpiRegistry, descriptorExecutor);

        // Proxy-aware vote sync (V3/V4): on a shared-DB network, keep the in-memory party
        // view network-wide (Premium + proxy.enabled). Fast layer = a DB-backed outbox
        // event bus (self-hosted, no Redis); safety net = a periodic DB reconcile. Only
        // meaningful when the vote-party exists (it's the one network-divergent view).
        if (edition().proxySyncEnabled() && voteConfig.isProxyEnabled() && votePartyService != null) {
            String serverId = voteConfig.getProxyServerId().isBlank()
                    ? UUID.randomUUID().toString()
                    : voteConfig.getProxyServerId();
            OutboxProxyEventBus proxyBus = new OutboxProxyEventBus(
                    plugin, syncEventRepository, serverId,
                    voteConfig.getProxyEventPollSeconds(), voteConfig.getProxyEventRetentionMinutes());
            votePartyService.setEventPublisher(proxyBus);
            new ProxyVoteSyncService(plugin, votePartyService, broadcastService, proxyBus,
                    voteConfig.getProxyReconcileSeconds()).start();
        }

        reminderService = new VoteReminderService(plugin, voteService, voteConfig, settingsService,
                settingsRepository);

        streakFreezeService = new StreakFreezeService(playerRepository, voteConfig);
        voteGiftService = new VoteGiftService(playerRepository, voteConfig);
        voteReconciliationService = new VoteReconciliationService(
                voteService, recordRepository, voteConfig, plugin.getLogger());

        // Purge old vote records on startup
        voteService.purgeOldRecords();
        // One-time, idempotent free Streak Freeze back-fill for existing players
        voteService.initializeFreezesForExistingPlayers();
    }

    /**
     * The configured sites with the edition limit applied. Logs a warning naming the limit when sites were
     * dropped, so the operator knows why a site is missing.
     */
    private @NotNull Map<String, VoteSite> loadedSites() {
        Map<String, VoteSite> configured = voteConfig.getVoteSites();
        Map<String, VoteSite> loaded = edition().limitSites(configured);
        if (loaded.size() < configured.size()) {
            final int max = edition().maxVoteSites();
            final int configuredCount = configured.size();
            logger.log(Level.WARNING, () -> String.format(
                    "The Free edition loads up to %d vote sites, but sites.yml defines %d. Only the first %d are "
                            + "used; JExVote Premium has no site limit.", max, configuredCount, max));
        }
        return loaded;
    }

    /** Applies {@code display.currency-style}: coin / crystal icons only where JExEconomy provides them. */
    private void applyCurrencyDisplay() {
        boolean jexEconomy = Bukkit.getPluginManager().getPlugin("JExEconomy") != null;
        VoteRewardDescriber.configure(CurrencyDisplay.resolve(
                voteConfig.getCurrencyStyle(), jexEconomy, voteConfig.getCurrencyNames()));
    }

    /**
     * Reloads config.yml, rewards.yml and sites.yml and applies them to every running service: sites (with the
     * edition limit), rewards, streak settings, the weekend bonus, freezes, the vote party and the currency
     * display. The Votifier port, the database and starting or stopping the vote party need a restart.
     */
    public void reload() {
        voteConfig.load();
        rewardConfig.load();
        partyConfig.load();
        effectsConfig.load();
        applyCurrencyDisplay();
        reminderService.start();
        rewardService.setStreaksEnabled(voteConfig.isFeatureStreaks());
        voteService.reload(
                loadedSites(),
                voteConfig.getStreakTimeoutHours(),
                voteConfig.getStreakCommands(),
                voteConfig.getRecordRetentionDays(),
                voteConfig.getStreakClaimMode() == VoteConfig.StreakClaimMode.MANUAL,
                new MultiplierService.Settings(
                        voteConfig.isWeekendMultiplierEnabled(),
                        voteConfig.getWeekendMultiplierFactor(),
                        voteConfig.getWeekendMultiplierDays(),
                        voteConfig.getWeekendMultiplierTimezone()),
                voteConfig.getFreezeSettings(),
                rewardConfig.getDefaultRewards(),
                rewardConfig.getGuaranteedRewards(),
                rewardConfig.getStreakRewards(),
                rewardConfig.getSiteRewards(),
                voteConfig.getCommandsOnVote());
        if (votePartyService != null) {
            votePartyService.reload(rewardConfig.getVotePartyRewards(), rewardConfig.getVotePartyPool(),
                    voteConfig.getVotePartyTarget());
        }
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
                            } else if (!Boolean.TRUE.equals(success)) {
                                logger.warning("Vote processing returned false for "
                                        + result.vote().username()
                                        + " on " + result.vote().serviceName());
                            }
                            // The "{player} voted" broadcast now fires inside processVote,
                            // so every ingestion path announces the voter - not just this one.
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
        NormalProfileRewards.install(plugin, pendingRewardRepository, voteService::deliverPendingRewards);
        pm.registerEvents(settingsService, plugin);
        settingsService.loadOnlinePlayers();
        reminderService.start();
        if (voteReconciliationService != null) {
            pm.registerEvents(voteReconciliationService, plugin);
        }
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
        CommandTreeMerger.addMissingSubcommands(plugin, "commands/vote.yml");
        CommandTreeMerger.addMissingSubcommands(plugin, "commands/jexvote.yml");

        var voteCommandHandler = new VoteCommandHandler(voteService, leaderboardService, features, overviewView,
                rewardsView, leaderboardView, streakFreezeService, voteGiftService);
        voteCommandHandler.setShopView(shopView);
        voteCommandHandler.setStreakAndPartyViews(streakView, partyView);
        voteCommandHandler.setSettingsView(settingsView);

        var bedrockBridge = new BedrockFormBridge();
        if (bedrockBridge.isAvailable()) {
            var bedrockForms = new VoteBedrockForms(bedrockBridge, voteService, features,
                    leaderboardService, rewardService, rewardConfig,
                    streakClaimService, multiplierService, rewardStatsService,
                    streakFreezeService, voteGiftService);
            bedrockForms.setPartyService(votePartyService);
            bedrockForms.setShopService(shopService);
            bedrockForms.setSettingsForm(new VoteSettingsForm(bedrockBridge, settingsService));
            voteCommandHandler.setBedrockForms(bedrockForms);
            bedrockFormsHooked = true;
        }
        factory.registerTree(new File(plugin.getDataFolder(), "commands/vote.yml"),
                voteCommandHandler.handlerMap(),
                messages, registry);
        var adminHandler = new VoteAdminHandler(plugin, voteService, voteConfig, features, adminStatus());
        adminHandler.setSettingsEraser(uuid -> {
            reminderService.forget(uuid);
            return settingsService.delete(uuid);
        });
        factory.registerTree(new File(plugin.getDataFolder(), "commands/jexvote.yml"),
                adminHandler.handlerMap(), messages, registry);

        factory.registerAllCommandsAndListeners();
        logger.info("Registered 2 command trees: /vote, /jexvote");
    }

    /** The live state {@code /jexvote info} reports, read at the moment the command runs. */
    private @NotNull AdminStatus adminStatus() {
        return new AdminStatus() {
            @Override public boolean votifierRunning() { return votifierServer != null && votifierServer.isRunning(); }
            @Override public boolean restApiRunning() { return restApiServer != null && restApiServer.isRunning(); }
            @Override public boolean placeholdersHooked() { return placeholders != null; }
            @Override public boolean bedrockFormsHooked() { return bedrockFormsHooked; }
            @Override public @Nullable VotePartyService party() { return votePartyService; }
            @Override public int configuredSiteCount() { return voteConfig.getVoteSites().size(); }
            @Override public int shopItemCount() { return rewardConfig.getVoteShopItems().size(); }
            @Override public void reload() { JExVote.this.reload(); }
        };
    }

    private void registerViews() {
        var pm = Bukkit.getPluginManager();

        shopService = new VoteShopService(plugin, playerRepository, rewardService, rewardConfig);
        overviewView = new VoteOverviewView(plugin, voteService, features, rewardConfig, streakFreezeService);
        leaderboardView = new VoteLeaderboardView(plugin, leaderboardService);
        streakView = new VoteStreakView(plugin, voteService, rewardService, streakClaimService);
        rewardsView = new VoteRewardsView(plugin, features, rewardConfig, multiplierService,
                rewardStatsService, streakFreezeService, voteGiftService);
        partyView = new VotePartyView(rewardConfig, votePartyService, rewardStatsService);
        shopView = new VoteShopView(plugin, shopService);
        var luckyView = new VoteLuckyView(rewardConfig, rewardStatsService);
        settingsView = new VoteSettingsView(plugin, settingsService, voteConfig);

        overviewView.setMultipliers(multiplierService);
        overviewView.setParty(votePartyService);
        overviewView.setLeaderboardView(leaderboardView);
        overviewView.setStreakView(streakView);
        overviewView.setRewardsView(rewardsView);
        overviewView.setShopView(shopView);
        overviewView.setPartyView(partyView);
        overviewView.setSettingsView(settingsView);
        settingsView.setOverviewView(overviewView);
        leaderboardView.setOverviewView(overviewView);
        streakView.setOverviewView(overviewView);
        rewardsView.setParty(votePartyService);
        rewardsView.setOverviewView(overviewView);
        rewardsView.setLuckyView(luckyView);
        partyView.setOverviewView(overviewView);
        shopView.setOverviewView(overviewView);
        luckyView.setRewardsView(rewardsView);

        for (VoteBaseView view : List.of(overviewView, leaderboardView, streakView, rewardsView, partyView,
                shopView, luckyView, settingsView)) {
            pm.registerEvents(view, plugin);
        }
    }

    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholders = new VotePlaceholderExpansion(playerRepository, votePartyService, rewardStatsService);
            placeholders.register();
            logger.info("Registered PlaceholderAPI expansion: %jexvote_<placeholder>%");
        }
    }

    private void registerApiProvider() {
        voteProvider = new VoteProviderImpl(voteService, leaderboardService,
                votePartyService, edition().writeHooksEnabled());
        JExVoteAPIImpl apiImpl = new JExVoteAPIImpl(voteProvider, rewardSpiRegistry);
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
