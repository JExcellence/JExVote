package de.jexcellence.vote.service;

import de.jexcellence.economy.api.EconomyProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pays out {@code currency}-type vote rewards. JExPlatform's
 * {@link de.jexcellence.jexplatform.reward.impl.CurrencyReward} has no economy
 * plugin on its classpath, so it delegates to a host-installed depositor — this
 * one routes <b>JExEconomy first, then Vault</b>. Both are soft dependencies:
 * each path is guarded by a plugin-presence check and a {@code Throwable} catch,
 * so the server keeps working when neither is installed (the reward is a no-op).
 *
 * @author JExcellence
 */
public final class RewardEconomy {

    private final Logger logger;

    public RewardEconomy(@NotNull Logger logger) {
        this.logger = logger;
    }

    /**
     * Deposits {@code amount} of {@code currency} into the player's account.
     *
     * @return a future resolving to {@code true} on a successful deposit
     */
    public @NotNull CompletableFuture<Boolean> deposit(@NotNull Player player,
                                                       @NotNull String currency, double amount) {
        if (amount <= 0.0) {
            return CompletableFuture.completedFuture(true);
        }
        CompletableFuture<Boolean> viaJexEconomy = tryJexEconomy(player, currency, amount);
        if (viaJexEconomy != null) {
            return viaJexEconomy;
        }
        return CompletableFuture.completedFuture(tryVault(player, amount));
    }

    private CompletableFuture<Boolean> tryJexEconomy(@NotNull Player player,
                                                     @NotNull String currency, double amount) {
        try {
            if (Bukkit.getPluginManager().getPlugin("JExEconomy") == null) {
                return null;
            }
            RegisteredServiceProvider<EconomyProvider> rsp =
                    Bukkit.getServicesManager().getRegistration(EconomyProvider.class);
            if (rsp == null) {
                return null;
            }
            return rsp.getProvider().deposit(player, currency, amount, null, "vote reward")
                    .thenApply(result -> result != null && result.isSuccess());
        } catch (Throwable ex) {
            logger.log(Level.WARNING, "JExEconomy deposit failed, falling back to Vault", ex);
            return null;
        }
    }

    private boolean tryVault(@NotNull Player player, double amount) {
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                return false;
            }
            RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp =
                    Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            if (rsp == null) {
                return false;
            }
            return rsp.getProvider().depositPlayer(player, amount).transactionSuccess();
        } catch (Throwable ex) {
            logger.log(Level.WARNING, "Vault deposit failed", ex);
            return false;
        }
    }
}
