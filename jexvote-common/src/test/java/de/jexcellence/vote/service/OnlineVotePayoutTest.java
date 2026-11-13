package de.jexcellence.vote.service;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.jexplatform.reward.impl.ItemReward;
import de.jexcellence.vote.api.event.VoteRewardClaimedEvent;
import de.jexcellence.vote.api.model.VoteSnapshot;
import de.jexcellence.vote.api.reward.VoteRewardDescriptor;
import de.jexcellence.vote.database.entity.PendingVoteRewardEntity;
import de.jexcellence.vote.model.Vote;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OnlineVotePayoutTest {

    private static final String SERVICE = "TopG";
    private static final String DAILY_COMMAND = "say thanks {player}";

    private ServerMock server;
    private final List<PendingVoteRewardEntity> stored = new ArrayList<>();
    private final List<List<VoteRewardDescriptor>> queuedBonus = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void followUpsWaitUntilTheItemsArrived() {
        GateReward gate = new GateReward();
        PlayerMock player = server.addPlayer();
        OnlineVotePayout payout = payout(OfflineVoteRewardQueueTest.serviceWith(List.of(gate)), List.of(),
                (target, task, retired) -> task.run());

        payout.pay(paidVote(player, false));

        assertTrue(events.isEmpty(), "the reward event fired before the items arrived");
        gate.release.complete(true);
        assertEquals(1, events.size());
        assertInstanceOf(VoteRewardClaimedEvent.class, events.get(0));
        assertTrue(stored.isEmpty());
    }

    @Test
    void playerGoneBeforeThePayoutGetsEverythingOnTheNextJoin() {
        ItemReward diamonds = new ItemReward("DIAMOND", 2, null, null, null, null, false, false);
        PlayerMock player = server.addPlayer();
        OnlineVotePayout payout = payout(OfflineVoteRewardQueueTest.serviceWith(List.of(diamonds)),
                List.of(DAILY_COMMAND), (target, task, retired) -> retired.run());

        payout.pay(paidVote(player, true));

        assertEquals(1, stored.size());
        String data = stored.get(0).getRewardData();
        assertTrue(data.contains("DIAMOND") && data.contains("say thanks"), data);
        assertEquals(1, queuedBonus.size());
        assertTrue(events.isEmpty());
    }

    @Test
    void playerGoneAfterTheItemsKeepsOnlyTheFollowUps() {
        ItemReward diamonds = new ItemReward("DIAMOND", 2, null, null, null, null, false, false);
        PlayerMock player = server.addPlayer();
        AtomicInteger hops = new AtomicInteger();
        OnlineVotePayout payout = payout(OfflineVoteRewardQueueTest.serviceWith(List.of(diamonds)),
                List.of(DAILY_COMMAND), (target, task, retired) -> {
                    if (hops.getAndIncrement() == 0) {
                        task.run();
                    } else {
                        retired.run();
                    }
                });

        payout.pay(paidVote(player, true));

        assertTrue(player.getInventory().contains(Material.DIAMOND, 2));
        assertEquals(1, stored.size());
        String data = stored.get(0).getRewardData();
        assertTrue(data.contains("say thanks"), data);
        assertFalse(data.contains("DIAMOND"), "the delivered items were queued a second time: " + data);
        assertEquals(1, queuedBonus.size());
        assertTrue(events.isEmpty());
    }

    private OnlineVotePayout payout(@NotNull VoteRewardService rewards, @NotNull List<String> dailyCommands,
                                    @NotNull PlayerRewardChain.PlayerThread playerTasks) {
        VoteCommandSet commands = new VoteCommandSet(dailyCommands, Map.of());
        OnlineVotePayout.Hooks hooks = new OnlineVotePayout.Hooks(playerTasks, stored::add,
                (voter, service, bonus) -> queuedBonus.add(bonus), () -> null, events::add);
        return new OnlineVotePayout(Logger.getLogger("test"), rewards, mock(VoteBroadcastService.class), commands,
                hooks);
    }

    private static PaidVote paidVote(@NotNull PlayerMock player, boolean firstDailyBonus) {
        Vote vote = new Vote(player.getName(), SERVICE, "127.0.0.1", Instant.now());
        return new PaidVote(player, vote, player.getUniqueId(), mock(VoteSnapshot.class), 1, firstDailyBonus,
                PaidVote.FreezeNotice.NONE, List.of());
    }

    static final class GateReward extends AbstractReward {

        final CompletableFuture<Boolean> release = new CompletableFuture<>();

        GateReward() {
            super("gate");
        }

        @Override
        public @NotNull CompletableFuture<Boolean> grant(@NotNull Player player) {
            return release;
        }

        @Override
        public @NotNull String descriptionKey() {
            return "reward.gate";
        }
    }
}
