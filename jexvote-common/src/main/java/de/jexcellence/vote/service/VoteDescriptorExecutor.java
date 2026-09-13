package de.jexcellence.vote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.jexcellence.vote.api.reward.VoteRewardDescriptor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    private static final String TYPE = "t";
    private static final String AMOUNT = "amount";

    private final Logger logger;
    private final RewardEconomy economy;
    private final PointsGranter points;
    private final ObjectMapper mapper = new ObjectMapper();

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

    // ── Offline serialization (queue SPI/pre-reward descriptors for a returning voter) ──

    /**
     * Encodes descriptors to a compact JSON array for the offline pending queue. Returns
     * {@code null} on failure (the caller then skips queuing rather than storing garbage).
     */
    public @Nullable String serialize(@NotNull List<VoteRewardDescriptor> descriptors) {
        try {
            List<Map<String, Object>> out = new ArrayList<>(descriptors.size());
            for (VoteRewardDescriptor descriptor : descriptors) {
                out.add(toMap(descriptor));
            }
            return mapper.writeValueAsString(out);
        } catch (Exception ex) {
            logger.log(Level.WARNING, ex, () -> "Failed to serialize vote descriptors for offline queue");
            return null;
        }
    }

    /** Decodes a JSON array produced by {@link #serialize}; skips any malformed/unknown entry. */
    @SuppressWarnings("unchecked")
    public @NotNull List<VoteRewardDescriptor> deserialize(@NotNull String json) {
        List<VoteRewardDescriptor> out = new ArrayList<>();
        try {
            List<Map<String, Object>> raw = mapper.readValue(json, List.class);
            for (Map<String, Object> entry : raw) {
                VoteRewardDescriptor descriptor = fromMap(entry);
                if (descriptor != null) {
                    out.add(descriptor);
                }
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, ex, () -> "Failed to parse serialized vote descriptors");
        }
        return out;
    }

    /** Decodes and grants a serialized descriptor blob to a returning player; reports what was given. */
    public @NotNull CompletableFuture<List<String>> grantSerialized(@NotNull Player player,
                                                                    @NotNull String json) {
        List<VoteRewardDescriptor> descriptors = deserialize(json);
        grant(player, descriptors);
        return CompletableFuture.completedFuture(describe(descriptors));
    }

    /** Short human-readable labels for a delivery summary. */
    public @NotNull List<String> describe(@NotNull List<VoteRewardDescriptor> descriptors) {
        List<String> out = new ArrayList<>(descriptors.size());
        for (VoteRewardDescriptor descriptor : descriptors) {
            out.add(describeOne(descriptor));
        }
        return out;
    }

    private @NotNull Map<String, Object> toMap(@NotNull VoteRewardDescriptor descriptor) {
        Map<String, Object> map = new LinkedHashMap<>();
        switch (descriptor) {
            case VoteRewardDescriptor.Item item -> {
                map.put(TYPE, "item");
                map.put("material", item.material().name());
                map.put(AMOUNT, item.amount());
                if (item.displayName() != null) {
                    map.put("name", item.displayName());
                }
            }
            case VoteRewardDescriptor.Command command -> {
                map.put(TYPE, "cmd");
                map.put("template", command.template());
            }
            case VoteRewardDescriptor.Currency currency -> {
                map.put(TYPE, "cur");
                map.put("id", currency.currencyId());
                map.put(AMOUNT, currency.amount());
            }
            case VoteRewardDescriptor.Points p -> {
                map.put(TYPE, "pts");
                map.put(AMOUNT, p.amount());
            }
            default -> throw new IllegalStateException("Unhandled vote reward descriptor: " + descriptor);
        }
        return map;
    }

    private @Nullable VoteRewardDescriptor fromMap(@NotNull Map<String, Object> map) {
        String type = String.valueOf(map.get(TYPE));
        switch (type) {
            case "item" -> {
                Material material = Material.matchMaterial(String.valueOf(map.get("material")));
                if (material == null) {
                    logger.log(Level.WARNING, () -> "Skipping serialized item with unknown material: " + map.get("material"));
                    return null;
                }
                int amount = Math.max(1, ((Number) map.get(AMOUNT)).intValue());
                Object name = map.get("name");
                return new VoteRewardDescriptor.Item(material, amount, name == null ? null : name.toString());
            }
            case "cmd" -> {
                return new VoteRewardDescriptor.Command(String.valueOf(map.get("template")));
            }
            case "cur" -> {
                return new VoteRewardDescriptor.Currency(
                        String.valueOf(map.get("id")), ((Number) map.get(AMOUNT)).doubleValue());
            }
            case "pts" -> {
                return new VoteRewardDescriptor.Points(((Number) map.get(AMOUNT)).intValue());
            }
            default -> {
                logger.log(Level.WARNING, () -> "Unknown serialized descriptor type: " + type);
                return null;
            }
        }
    }

    private @NotNull String describeOne(@NotNull VoteRewardDescriptor descriptor) {
        return switch (descriptor) {
            case VoteRewardDescriptor.Item item -> item.amount() + "x " + item.material().name();
            case VoteRewardDescriptor.Command ignored -> "a command reward";
            case VoteRewardDescriptor.Currency currency ->
                    String.format(Locale.ROOT, "%.0f %s", currency.amount(), currency.currencyId());
            case VoteRewardDescriptor.Points p -> p.amount() + " vote-points";
            default -> "a reward";
        };
    }
}
