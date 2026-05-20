package de.jexcellence.vote;

import com.raindropcentral.commands.CommandFactory;
import com.raindropcentral.commands.v2.argument.ArgumentType;
import com.raindropcentral.commands.v2.argument.ArgumentTypeRegistry;
import de.jexcellence.jehibernate.core.JEHibernate;
import de.jexcellence.jexplatform.JExPlatform;
import de.jexcellence.jexplatform.logging.LogLevel;
import de.jexcellence.jexplatform.reward.RewardRegistry;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.api.JExVoteAPI;
import de.jexcellence.vote.command.R18nCommandMessages;
import de.jexcellence.vote.command.VoteAdminHandler;
import de.jexcellence.vote.command.VoteCommandHandler;
import de.jexcellence.vote.config.VoteConfig;
import de.jexcellence.vote.config.VoteRewardConfig;
import de.jexcellence.vote.database.repository.PendingVoteRewardRepository;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import de.jexcellence.vote.database.repository.VoteRecordRepository;
import de.jexcellence.vote.listener.PlayerJoinListener;
import de.jexcellence.vote.placeholder.VotePlaceholderExpansion;
import de.jexcellence.vote.server.VotifierKeyManager;
import de.jexcellence.vote.server.VotifierServer;
import de.jexcellence.vote.service.VoteBroadcastService;
import de.jexcellence.vote.service.VoteLeaderboardService;
import de.jexcellence.vote.service.VoteRewardService;
import de.jexcellence.vote.service.VoteService;
import de.jexcellence.vote.view.VoteLeaderboardView;
import de.jexcellence.vote.view.VoteOverviewView;
import de.jexcellence.vote.view.VoteStreakView;
import me.devnatan.inventoryframework.ViewFrame;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.security.KeyPair;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class JExVote {

    private final JavaPlugin plugin;
    private final String edition;
    private final Logger logger;

    private JExPlatform platform;
    private JEHibernate jeHibernate;

    private VoteConfig voteConfig;
    private VoteRewardConfig rewardConfig;

    private VotePlayerRepository playerRepository;
    private VoteRecordRepository recordRepository;
    private PendingVoteRewardRepository pendingRewardRepository;

    private VoteService voteService;
    private VoteRewardService rewardService;
    private VoteBroadcastService broadcastService;
    private VoteLeaderboardService leaderboardService;

    private VotifierServer votifierServer;
    private VotePlaceholderExpansion placeholders;
    private VoteProviderImpl voteProvider;
    private ViewFrame viewFrame;

    protected JExVote(@NotNull JavaPlugin plugin, @NotNull String edition) {
        this.plugin = plugin;
        this.edition = edition;
        this.logger = plugin.getLogger();
    }

    protected abstract int metricsId();
    protected abstract VoteEdition edition();

    public void onLoad() {
        logger.info("Loading JExVote " + edition + " Edition v" + plugin.getDescription().getVersion());

        platform = JExPlatform.builder(plugin)
                .withLogLevel(LogLevel.INFO)
                .enableTranslations("en_US", "de_DE", "cs_CZ", "sk_SK")
                .enableMetrics(metricsId())
                .enableRewards()
                .build();

        voteConfig = new VoteConfig(plugin);
        voteConfig.load();
    }

    public void onEnable() {
        try {
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

            logger.info("JExVote " + edition + " enabled — Votifier on port " + voteConfig.getServerPort());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to enable JExVote", e);
            Bukkit.getPluginManager().disablePlugin(plugin);
        }
    }

    public void onDisable() {
        if (votifierServer != null) {
            votifierServer.shutdown();
        }
        if (placeholders != null) {
            try { placeholders.unregister(); } catch (Throwable ignored) {}
        }
        if (voteProvider != null) {
            try {
                Bukkit.getServicesManager().unregister(JExVoteAPI.class, voteProvider);
            } catch (Throwable ignored) {}
        }
        // ViewFrame is cleaned up by the plugin lifecycle
        if (jeHibernate != null) {
            jeHibernate.close();
        }
        if (platform != null) {
            platform.shutdown();
        }
        logger.info("JExVote disabled");
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
        var target = new java.io.File(plugin.getDataFolder(),
                resourcePath.replace('/', java.io.File.separatorChar));
        if (!target.exists()) {
            target.getParentFile().mkdirs();
            plugin.saveResource(resourcePath, false);
        }
    }

    private void initializeRepositories() {
        var repos = jeHibernate.repositories();
        playerRepository = repos.get(VotePlayerRepository.class);
        recordRepository = repos.get(VoteRecordRepository.class);
        pendingRewardRepository = repos.get(PendingVoteRewardRepository.class);
    }

    private void initializeServices() {
        RewardRegistry rewardRegistry = platform.rewardRegistry().orElseThrow(
                () -> new IllegalStateException("Reward registry not initialized"));

        rewardConfig = new VoteRewardConfig(plugin, rewardRegistry);
        rewardConfig.load();

        rewardService = new VoteRewardService(
                logger, rewardRegistry,
                rewardConfig.getDefaultRewards(),
                rewardConfig.getStreakRewards(),
                rewardConfig.getSiteRewards(),
                voteConfig.getCommandsOnVote());

        broadcastService = new VoteBroadcastService(
                voteConfig.getBroadcastMode(),
                voteConfig.getBroadcastCooldownSeconds(),
                voteConfig.isPrivateMessageEnabled());
        leaderboardService = new VoteLeaderboardService(playerRepository);

        voteService = new VoteService(
                plugin, playerRepository, recordRepository,
                pendingRewardRepository, rewardService,
                voteConfig.getVoteSites());
    }

    private void initializeVotifierServer() {
        try {
            KeyPair keyPair = VotifierKeyManager.loadOrGenerate(
                    plugin.getDataFolder().toPath(), logger);

            String token = voteConfig.getServerToken();
            if (token.isEmpty()) {
                token = VotifierKeyManager.generateToken();
                logger.info("Generated Votifier token. Add this to your vote site config.");
                logger.info("Token: " + token);
                logger.info("Public key: " + VotifierKeyManager.encodePublicKey(keyPair.getPublic()));
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
                                logger.log(Level.SEVERE, "Error processing vote for "
                                        + result.vote().username(), error);
                                return;
                            }
                            if (success) {
                                PlatformScheduler.of(plugin).runSync(() -> {
                                    var voter = Bukkit.getPlayerExact(result.vote().username());
                                    broadcastService.broadcastVote(
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
                    var lower = partial.toLowerCase(java.util.Locale.ROOT);
                    return voteService.getVoteSites().values().stream()
                            .map(de.jexcellence.vote.model.VoteSite::serviceName)
                            .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(lower))
                            .toList();
                }));

        // Save command YAMLs to data folder on first run so users can edit
        // names, aliases, permissions, and descriptions.
        saveDefaultResource("commands/vote.yml");
        saveDefaultResource("commands/jexvote.yml");

        factory.registerTree(new java.io.File(plugin.getDataFolder(), "commands/vote.yml"),
                new VoteCommandHandler(voteService, leaderboardService, voteConfig, viewFrame).handlerMap(),
                messages, registry);
        factory.registerTree(new java.io.File(plugin.getDataFolder(), "commands/jexvote.yml"),
                new VoteAdminHandler(plugin, edition(), voteService, voteConfig, rewardConfig).handlerMap(),
                messages, registry);

        factory.registerAllCommandsAndListeners();
        logger.info("Registered 2 command trees: /vote, /jexvote");
    }

    private void registerViews() {
        viewFrame = ViewFrame.create(plugin)
                .with(
                        new VoteOverviewView(plugin, voteService, broadcastService),
                        new VoteLeaderboardView(leaderboardService),
                        new VoteStreakView(plugin, voteService, rewardService)
                )
                .register();
    }

    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholders = new VotePlaceholderExpansion(playerRepository);
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

    public @NotNull JavaPlugin getPlugin() { return plugin; }
    public @NotNull VoteService getVoteService() { return voteService; }
    public @NotNull VoteLeaderboardService getLeaderboardService() { return leaderboardService; }
    public @NotNull VoteConfig getVoteConfig() { return voteConfig; }
    public @NotNull ViewFrame getViewFrame() { return viewFrame; }
}
