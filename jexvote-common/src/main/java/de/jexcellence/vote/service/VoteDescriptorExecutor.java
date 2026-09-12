package de.jexcellence.vote.service;

import de.jexcellence.vote.api.reward.VoteRewardDescriptor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Translates platform-free {@link VoteRewardDescriptor}s (from the V2 reward SPI)
 * into real grants through JExVote's host integrations, so third-party providers
 * never touch the internal reward framework. Item and command grants run on the
 * caller's thread (the executor is invoked on the player's region thread); currency
 * and points route through the same async paths the internal rewards use.
 *
 * @author JExcellence
 */
public final class VoteDescriptorExecutor {

    /** Grants vote-points to a player; see {@link VoteService#grantVotePoints}. */
    @FunctionalInterface
    public interface PointsGranter {
        void grant(@NotNull UUID uuid, int amount, @NotNull String reason);
    }

    private static final String SPI_REASON = "vote reward SPI";

    private final Logger logger;
    private final RewardEconomy economy;
    private final PointsGranter points;

    public VoteDescriptorExecutor(@NotNull Logger logger, @NotNull RewardEconomy economy,
                                  @NotNull PointsGranter points) {
        this.logger = logger;
        this.economy = economy;
        this.points = points;
    }

    /**
     * Grants every descriptor to an online player, isolating a failure of one so the
     * rest still deliver.
     *
     * @param player      the online recipient
     * @param descriptors the SPI reward descriptors
     */
    public void grant(@NotNull Player player, @NotNull List<VoteRewardDescriptor> descriptors) {
        for (VoteRewardDescriptor descriptor : descriptors) {
            try {
                grantOne(player, descriptor);
            } catch (Exception ex) {
                logger.log(Level.WARNING, ex, () ->
                        "Failed to grant an SPI vote reward to " + player.getName());
            }
        }
    }

    private void grantOne(@NotNull Player player, @NotNull VoteRewardDescriptor descriptor) {
        switch (descriptor) {
            case VoteRewardDescriptor.Item item -> giveItem(player, item);
            case VoteRewardDescriptor.Command command -> runCommand(player, command);
            case VoteRewardDescriptor.Currency currency ->
                    economy.deposit(player, currency.currencyId(), currency.amount());
            case VoteRewardDescriptor.Points p -> points.grant(player.getUniqueId(), p.amount(), SPI_REASON);
            default -> throw new IllegalStateException("Unhandled vote reward descriptor: " + descriptor);
        }
    }

    private void giveItem(@NotNull Player player, @NotNull VoteRewardDescriptor.Item item) {
        ItemStack stack = new ItemStack(item.material(), item.amount());
        String displayName = item.displayName();
        if (displayName != null) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.displayName(MiniMessage.miniMessage().deserialize(displayName));
                stack.setItemMeta(meta);
            }
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
        overflow.values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    private void runCommand(@NotNull Player player, @NotNull VoteRewardDescriptor.Command command) {
        String resolved = command.template()
                .replace("%player%", player.getName())
                .replace("{player}", player.getName());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
    }
}
