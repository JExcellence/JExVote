package de.jexcellence.vote.service;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.fasterxml.jackson.annotation.JsonCreator;
import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.reward.CoreRewardTypes;
import de.jexcellence.jexplatform.reward.RewardRegistry;
import de.jexcellence.jexplatform.reward.RewardType;
import de.jexcellence.jexplatform.reward.impl.ItemReward;
import de.jexcellence.vote.reward.LuckyReward;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineVoteRewardQueueTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void queuedLuckyItemRewardStillPaysOnJoin() {
        ItemReward diamonds = new ItemReward("DIAMOND", 2, null, null, null, null, false, false);
        LuckyReward jackpot = new LuckyReward(List.of(new LuckyReward.Entry("jackpot-diamonds", 1.0, diamonds, null)));
        VoteRewardService service = serviceWith(List.of(jackpot));

        String queued = service.serializeRewards("TopG", 1, List.of());
        assertNotNull(queued);

        PlayerMock player = server.addPlayer();
        service.grantSerializedRewards(player, queued).handle((given, error) -> given).join();

        assertTrue(player.getInventory().contains(Material.DIAMOND, 2), "queued lucky diamonds were lost: " + queued);
    }

    @Test
    void failedRewardDoesNotSwallowTheRewardsQueuedBehindIt() {
        ItemReward diamonds = new ItemReward("DIAMOND", 2, null, null, null, null, false, false);
        VoteRewardService service = serviceWith(List.of(new FailingReward(), diamonds));

        String queued = service.serializeRewards("TopG", 1, List.of());
        assertNotNull(queued);

        PlayerMock player = server.addPlayer();
        service.grantSerializedRewards(player, queued).handle((given, error) -> given).join();

        assertTrue(player.getInventory().contains(Material.DIAMOND, 2), "a failed reward dropped the diamonds: " + queued);
    }

    @Test
    void onlineRewardsRunOneAfterAnotherOnThePlayerThread() {
        ItemReward diamonds = new ItemReward("DIAMOND", 2, null, null, null, null, false, false);
        VoteRewardService service = serviceWith(List.of());
        AtomicInteger hops = new AtomicInteger();
        service.setPlayerThread((target, task, retired) -> {
            hops.incrementAndGet();
            task.run();
        });

        PlayerMock player = server.addPlayer();
        service.grantAll(List.of(new FailingReward(), diamonds), player, 1.0, "TopG").join();

        assertTrue(player.getInventory().contains(Material.DIAMOND, 2));
        assertEquals(2, hops.get());
    }

    @Test
    void rewardThatThrowsRightAwayDoesNotStopTheRest() {
        ItemReward diamonds = new ItemReward("DIAMOND", 2, null, null, null, null, false, false);
        VoteRewardService service = serviceWith(List.of());

        PlayerMock player = server.addPlayer();
        service.grantAll(List.of(new ThrowingReward(), diamonds), player, 1.0, "TopG").join();

        assertTrue(player.getInventory().contains(Material.DIAMOND, 2));
    }

    @Test
    void playerWhoLeftDoesNotStallTheRewardChain() {
        ItemReward diamonds = new ItemReward("DIAMOND", 2, null, null, null, null, false, false);
        VoteRewardService service = serviceWith(List.of());
        service.setPlayerThread((target, task, retired) -> retired.run());

        PlayerMock player = server.addPlayer();

        assertTrue(service.grantAll(List.of(diamonds, diamonds), player, 1.0, "TopG").isDone());
        assertFalse(player.getInventory().contains(Material.DIAMOND));
    }

    @Test
    void rewardsOfAPlayerWhoLeftOnlineAreKeptForTheNextJoin() {
        ItemReward diamonds = new ItemReward("DIAMOND", 2, null, null, null, null, false, false);
        VoteRewardService service = serviceWith(List.of());
        List<String> kept = new ArrayList<>();
        service.setLeftoverQueue((uuid, source, data) -> kept.add(source + "|" + data));
        service.setPlayerThread((target, task, retired) -> retired.run());

        PlayerMock player = server.addPlayer();
        VoteRewardService.GrantTally tally = service.grantAll(List.of(diamonds), player, 1.0, "TopG").join();

        assertEquals(1, kept.size());
        assertTrue(kept.getFirst().startsWith("TopG|") && kept.getFirst().contains("DIAMOND"));
        assertEquals(new VoteRewardService.GrantTally(1, 0), tally);
    }

    @Test
    void storedPayoutReturnsWhatAPlayerWhoLeftDidNotGet() {
        ItemReward diamonds = new ItemReward("DIAMOND", 2, null, null, null, null, false, false);
        ItemReward emeralds = new ItemReward("EMERALD", 3, null, null, null, null, false, false);
        VoteRewardService service = serviceWith(List.of(diamonds, emeralds));
        String queued = service.serializeRewards("TopG", 1, List.of());
        assertNotNull(queued);
        AtomicInteger hops = new AtomicInteger();
        service.setPlayerThread((target, task, retired) -> {
            if (hops.incrementAndGet() == 1) {
                task.run();
            } else {
                retired.run();
            }
        });

        PlayerMock player = server.addPlayer();
        VoteRewardService.StoredGrant grant = service.grantSerializedRewards(player, queued)
                .handle((given, error) -> given).join();

        assertTrue(player.getInventory().contains(Material.DIAMOND, 2));
        assertNotNull(grant.remaining());
        assertTrue(grant.remaining().contains("EMERALD") && !grant.remaining().contains("DIAMOND"));
    }

    @Test
    void streakTallyCountsDeliveredAndFailedRewards() {
        ItemReward diamonds = new ItemReward("DIAMOND", 2, null, null, null, null, false, false);
        VoteRewardService service = serviceWith(List.of());

        PlayerMock player = server.addPlayer();
        VoteRewardService.GrantTally tally = service.grantAll(List.of(new FailingReward(), diamonds), player, 1.0,
                "streak-claim").join();

        assertEquals(new VoteRewardService.GrantTally(1, 1), tally);
    }

    @Test
    void storedCommandsRunAfterTheItemsAndStayWhenThePlayerLeftFirst() {
        ItemReward diamonds = new ItemReward("DIAMOND", 2, null, null, null, null, false, false);
        VoteRewardService service = serviceWith(List.of(diamonds));
        String queued = service.serializeRewards("TopG", 1, List.of("say thanks {player}"));
        assertNotNull(queued);
        AtomicInteger hops = new AtomicInteger();
        service.setPlayerThread((target, task, retired) -> {
            if (hops.getAndIncrement() == 0) {
                task.run();
            } else {
                retired.run();
            }
        });

        PlayerMock player = server.addPlayer();
        VoteRewardService.StoredGrant grant = service.grantSerializedRewards(player, queued).join();

        assertTrue(player.getInventory().contains(Material.DIAMOND, 2));
        assertNotNull(grant.remaining());
        assertTrue(grant.remaining().contains("say thanks") && !grant.remaining().contains("DIAMOND"),
                grant.remaining());
    }

    @Test
    void unreadableStoredBlobIsKeptWhole() {
        VoteRewardService service = serviceWith(List.of());
        String broken = "{\"rewards\": [ not json";

        PlayerMock player = server.addPlayer();
        VoteRewardService.StoredGrant grant = service.grantSerializedRewards(player, broken).join();

        assertEquals(List.of(), grant.received());
        assertEquals(broken, grant.remaining());
    }

    @Test
    void unreadableRewardIsKeptWhileTheOthersArrive() {
        ItemReward diamonds = new ItemReward("DIAMOND", 2, null, null, null, null, false, false);
        VoteRewardService service = serviceWith(List.of(diamonds));
        String queued = service.serializeRewards("TopG", 1, List.of());
        assertNotNull(queued);
        String withUnknown = queued.replace("\"rewards\":[", "\"rewards\":[{\"type\":\"retired-type\"},");

        PlayerMock player = server.addPlayer();
        VoteRewardService.StoredGrant grant = service.grantSerializedRewards(player, withUnknown).join();

        assertTrue(player.getInventory().contains(Material.DIAMOND, 2));
        assertNotNull(grant.remaining());
        assertTrue(grant.remaining().contains("retired-type") && !grant.remaining().contains("DIAMOND"));
    }

    @Test
    void storedLuckyRewardWhosePrizeFailsIsLoggedAsUndelivered() {
        LuckyReward pool = new LuckyReward(List.of(new LuckyReward.Entry("broken", 1.0, new RefusingReward(), null)));
        VoteRewardService service = serviceWith(List.of(pool));
        String queued = service.serializeRewards("TopG", 1, List.of());
        assertNotNull(queued);
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                records.add(logRecord);
            }

            @Override
            public void flush() {
                records.size();
            }

            @Override
            public void close() {
                records.size();
            }
        };
        Logger logger = Logger.getLogger("test");
        logger.addHandler(capture);
        try {
            PlayerMock player = server.addPlayer();
            VoteRewardService.StoredGrant grant = service.grantSerializedRewards(player, queued).join();

            assertEquals(List.of(), grant.received());
            assertTrue(records.stream().anyMatch(logRecord -> logRecord.getLevel() == Level.WARNING
                    && logRecord.getMessage().contains(LuckyReward.TYPE_ID)
                    && logRecord.getMessage().contains("did not deliver")), "failed lucky prize was not logged");
        } finally {
            logger.removeHandler(capture);
        }
    }

    static VoteRewardService serviceWith(List<AbstractReward> defaults) {
        RewardRegistry registry = new RewardRegistry(JExLogger.of("OfflineVoteRewardQueueTest"));
        registry.register(CoreRewardTypes.ITEM);
        registry.register(CoreRewardTypes.COMMAND);
        registry.register(RewardType.plugin(FailingReward.TYPE_ID, "test", FailingReward.class));
        registry.register(RewardType.plugin(RefusingReward.TYPE_ID, "test", RefusingReward.class));
        registry.register(RewardType.plugin(LuckyReward.TYPE_ID, "jexvote", LuckyReward.class));
        MultiplierService multiplier = new MultiplierService(false, new MultiplierService.Settings(
                false, 1.0, Set.of(DayOfWeek.SATURDAY), ZoneId.of("Europe/Berlin")));
        return new VoteRewardService(Logger.getLogger("test"), registry, defaults, List.of(),
                Map.of(), Map.of(), List.of(), multiplier);
    }

    static final class FailingReward extends AbstractReward {

        static final String TYPE_ID = "failing";

        @JsonCreator
        FailingReward() {
            super(TYPE_ID);
        }

        @Override
        public @NotNull CompletableFuture<Boolean> grant(@NotNull Player player) {
            return CompletableFuture.failedFuture(new IllegalStateException("deposit rejected"));
        }

        @Override
        public @NotNull String descriptionKey() {
            return "reward.failing";
        }
    }

    static final class RefusingReward extends AbstractReward {

        static final String TYPE_ID = "refusing";

        @JsonCreator
        RefusingReward() {
            super(TYPE_ID);
        }

        @Override
        public @NotNull CompletableFuture<Boolean> grant(@NotNull Player player) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public @NotNull String descriptionKey() {
            return "reward.refusing";
        }
    }

    static final class ThrowingReward extends AbstractReward {

        ThrowingReward() {
            super("throwing");
        }

        @Override
        public @NotNull CompletableFuture<Boolean> grant(@NotNull Player player) {
            throw new IllegalStateException("economy unavailable");
        }

        @Override
        public @NotNull String descriptionKey() {
            return "reward.throwing";
        }
    }
}
