package de.jexcellence.vote.command;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.vote.gui.style.VoteFormat;
import de.jexcellence.vote.model.VoteSite;
import de.jexcellence.vote.service.VoteService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code /vote links} chat panel: every loaded vote site in config order, each with its ready state or
 * remaining cooldown. Java players get clickable links; Bedrock players get the address as plain text because
 * their chat cannot open links; the console, and a player whose cooldowns could not be loaded, gets name and
 * address.
 */
final class VoteLinksPanel {

    private static final Logger LOGGER = Logger.getLogger(VoteLinksPanel.class.getName());
    private static final String KEY = "vote.sites.";
    private static final String PARAM_NAME = "name";
    private static final String PARAM_URL = "url";

    private final VoteService voteService;
    private final Predicate<Player> bedrockCheck;
    private final PlatformScheduler scheduler;

    VoteLinksPanel(@NotNull VoteService voteService, @NotNull Predicate<Player> bedrockCheck) {
        this.voteService = voteService;
        this.bedrockCheck = bedrockCheck;
        this.scheduler = PlatformScheduler.of(JavaPlugin.getProvidingPlugin(VoteLinksPanel.class));
    }

    void send(@NotNull CommandSender sender) {
        Map<String, VoteSite> sites = voteService.getVoteSites();
        if (sites.isEmpty()) {
            r18n().msg(KEY + "no_sites").prefix().send(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            sendConsole(sender, sites.values());
            return;
        }
        voteService.voteCooldownsSeconds(player.getUniqueId())
                .whenComplete((cooldowns, failure) -> scheduler.runAtEntity(player, () -> {
                    if (failure == null) {
                        sendPlayer(player, sites.values(), cooldowns);
                        return;
                    }
                    LOGGER.log(Level.WARNING, failure, () -> "Could not load vote cooldowns for " + player.getName());
                    sendConsole(player, sites.values());
                }));
    }

    private void sendConsole(@NotNull CommandSender sender, @NotNull Collection<VoteSite> sites) {
        r18n().msg(KEY + "title").with("count", String.valueOf(sites.size())).send(sender);
        for (VoteSite site : sites) {
            r18n().msg(KEY + "entry-console-v2")
                    .with(PARAM_NAME, site.displayName())
                    .with(PARAM_URL, linkOrService(site))
                    .send(sender);
        }
    }

    private void sendPlayer(@NotNull Player player, @NotNull Collection<VoteSite> sites,
                            @NotNull Map<String, Long> cooldowns) {
        long ready = sites.stream().filter(site -> remaining(cooldowns, site) <= 0L).count();
        r18n().msg(KEY + "title-v2")
                .with("ready", VoteFormat.number(player, ready))
                .with("total", VoteFormat.number(player, sites.size()))
                .send(player);
        boolean bedrock = bedrockCheck.test(player);
        for (VoteSite site : sites) {
            sendRow(player, site, remaining(cooldowns, site), bedrock);
        }
        r18n().msg(KEY + (bedrock ? "footer-bedrock-v2" : "footer-v2")).send(player);
    }

    private void sendRow(@NotNull Player player, @NotNull VoteSite site, long seconds, boolean bedrock) {
        boolean isReady = seconds <= 0L;
        String layout;
        if (site.voteUrl() == null) {
            layout = "no-link";
        } else {
            layout = bedrock ? "bedrock" : "link";
        }
        r18n().msg(KEY + "entry-" + layout + (isReady ? "-ready-v2" : "-cooldown-v2"))
                .with(PARAM_NAME, site.displayName())
                .with(PARAM_URL, linkOrService(site))
                .with("time", isReady ? "" : VoteFormat.duration(player, seconds))
                .send(player);
    }

    private static long remaining(@NotNull Map<String, Long> cooldowns, @NotNull VoteSite site) {
        return cooldowns.getOrDefault(site.serviceName(), 0L);
    }

    private static @NotNull String linkOrService(@NotNull VoteSite site) {
        return site.voteUrl() != null ? site.voteUrl() : site.serviceName();
    }

    private static @NotNull R18nManager r18n() {
        return R18nManager.getInstance();
    }
}
