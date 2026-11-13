package de.jexcellence.vote.service;

import de.jexcellence.jexplatform.reward.AbstractReward;
import de.jexcellence.vote.database.entity.VotePlayerEntity;
import de.jexcellence.vote.database.repository.ClaimedStreakRewardRepository;
import de.jexcellence.vote.database.repository.VotePlayerRepository;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreakClaimServiceTest {

    private static final int DAY = 7;
    private static final String LOGGER_NAME = "test";

    private final UUID uuid = UUID.randomUUID();
    private final ClaimedStreakRewardRepository claims = mock(ClaimedStreakRewardRepository.class);
    private final VotePlayerRepository players = mock(VotePlayerRepository.class);
    private final VoteRewardService rewards = mock(VoteRewardService.class);
    private final Player player = mock(Player.class);

    @TempDir
    File dataFolder;

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("SaltyFeaRz");
        when(rewards.getStreakRewards()).thenReturn(Map.of(DAY, List.of(mock(AbstractReward.class))));
        when(claims.findClaimedDays(uuid)).thenReturn(CompletableFuture.completedFuture(Set.of()));
        VotePlayerEntity entity = mock(VotePlayerEntity.class);
        when(entity.getHighestStreak()).thenReturn(DAY);
        when(players.findByUuidAsync(uuid)).thenReturn(CompletableFuture.completedFuture(Optional.of(entity)));
    }

    @Test
    void everyRewardPaidIsSuccessAndRecordsTheClaim() {
        tally(2, 0);

        assertEquals(StreakClaimService.ClaimResult.SUCCESS, onlineService().claimMilestone(player, DAY).join());
        verify(claims).createClaim(uuid, DAY, false);
    }

    @Test
    void someRewardsFailedIsPartialAndStillRecordsTheClaim() {
        tally(1, 1);

        assertEquals(StreakClaimService.ClaimResult.PARTIAL, onlineService().claimMilestone(player, DAY).join());
        verify(claims).createClaim(uuid, DAY, false);
    }

    @Test
    void nothingPaidIsGrantFailedAndLeavesTheDayClaimable() {
        tally(0, 2);

        assertEquals(StreakClaimService.ClaimResult.GRANT_FAILED,
                onlineService().claimMilestone(player, DAY).join());
        verify(claims, never()).createClaim(eq(uuid), anyInt(), anyBoolean());
    }

    @Test
    void alreadyClaimedDayPaysNothing() {
        when(claims.findClaimedDays(uuid)).thenReturn(CompletableFuture.completedFuture(Set.of(DAY)));

        assertEquals(StreakClaimService.ClaimResult.ALREADY_CLAIMED,
                onlineService().claimMilestone(player, DAY).join());
        verify(rewards, never()).grantStreakReward(player, DAY);
    }

    @Test
    void playerGoneBeforeTheGrantFailsAndReleasesTheClaim() {
        StreakClaimService service = new StreakClaimService(Logger.getLogger(LOGGER_NAME), dataFolder, claims, players,
                rewards, (target, task, retired) -> retired.run(), Runnable::run);

        assertEquals(StreakClaimService.ClaimResult.GRANT_FAILED, service.claimMilestone(player, DAY).join());
        assertEquals(StreakClaimService.ClaimResult.GRANT_FAILED, service.claimMilestone(player, DAY).join(),
                "a retired claim stayed in flight and the next click got BUSY");
        verify(rewards, never()).grantStreakReward(player, DAY);
    }

    @Test
    void grantThrowingOnThePlayerThreadFailsAndReleasesTheClaim() {
        when(rewards.grantStreakReward(player, DAY)).thenThrow(new IllegalStateException("economy unavailable"));
        StreakClaimService service = onlineService();

        assertEquals(StreakClaimService.ClaimResult.GRANT_FAILED, service.claimMilestone(player, DAY).join());
        assertEquals(StreakClaimService.ClaimResult.GRANT_FAILED, service.claimMilestone(player, DAY).join());
    }

    @Test
    void rejectedClaimRecordStillCompletesTheClaim() {
        tally(1, 0);
        StreakClaimService service = new StreakClaimService(Logger.getLogger(LOGGER_NAME), dataFolder, claims, players,
                rewards, (target, task, retired) -> task.run(), task -> {
                    throw new RejectedExecutionException("shut down");
                });

        assertEquals(StreakClaimService.ClaimResult.SUCCESS, service.claimMilestone(player, DAY).join());
        assertEquals(StreakClaimService.ClaimResult.ALREADY_CLAIMED, service.claimMilestone(player, DAY).join());
    }

    @Test
    void unsavedClaimRecordKeepsTheDayClosedSoItCannotPayTwice() {
        tally(1, 0);
        doThrow(new IllegalStateException("db down")).when(claims).createClaim(uuid, DAY, false);
        StreakClaimService service = onlineService();

        assertEquals(StreakClaimService.ClaimResult.SUCCESS, service.claimMilestone(player, DAY).join());
        assertEquals(StreakClaimService.ClaimResult.ALREADY_CLAIMED, service.claimMilestone(player, DAY).join());
        verify(rewards, times(1)).grantStreakReward(player, DAY);
    }

    @Test
    void unsavedClaimStaysClosedAfterARestartUntilItIsSaved() {
        tally(1, 0);
        doThrow(new IllegalStateException("db down")).when(claims).createClaim(uuid, DAY, false);
        assertEquals(StreakClaimService.ClaimResult.SUCCESS, onlineService().claimMilestone(player, DAY).join());

        StreakClaimService restarted = onlineService();
        assertEquals(StreakClaimService.ClaimResult.ALREADY_CLAIMED, restarted.claimMilestone(player, DAY).join());

        doNothing().when(claims).createClaim(uuid, DAY, false);
        restarted.retryUnsavedClaims();
        assertEquals(List.of(), new UnsavedClaimStore(dataFolder, Logger.getLogger(LOGGER_NAME)).all());
        verify(rewards, times(1)).grantStreakReward(player, DAY);
    }

    @Test
    void unsavedAutoClaimIsSavedAgainAsAnAutoClaim() {
        doThrow(new IllegalStateException("db down")).when(claims).createClaim(uuid, DAY, true);
        StreakClaimService service = onlineService();
        service.markAutoClaimed(uuid, DAY);

        doNothing().when(claims).createClaim(uuid, DAY, true);
        onlineService().retryUnsavedClaims();

        verify(claims, times(2)).createClaim(uuid, DAY, true);
        verify(claims, never()).createClaim(uuid, DAY, false);
    }

    private void tally(int granted, int failed) {
        when(rewards.grantStreakReward(player, DAY)).thenReturn(
                CompletableFuture.completedFuture(new VoteRewardService.GrantTally(granted, failed)));
    }

    private StreakClaimService onlineService() {
        return new StreakClaimService(Logger.getLogger(LOGGER_NAME), dataFolder, claims, players, rewards,
                (target, task, retired) -> task.run(), Runnable::run);
    }
}
