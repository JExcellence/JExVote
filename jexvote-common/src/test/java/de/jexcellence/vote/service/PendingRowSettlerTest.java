package de.jexcellence.vote.service;

import de.jexcellence.vote.database.entity.PendingVoteRewardEntity;
import de.jexcellence.vote.database.repository.PendingVoteRewardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingRowSettlerTest {

    private static final long ROW_ID = 42L;
    private static final UUID PLAYER = UUID.randomUUID();
    private static final String ROW_DATA = "{\"rewards\":[{\"type\":\"item\"}],\"commands\":[]}";
    private static final String LEFTOVER = "{\"rewards\":[],\"commands\":[\"say hi\"]}";

    private final PendingVoteRewardRepository repository = mock(PendingVoteRewardRepository.class);
    private final PendingVoteRewardEntity row = mock(PendingVoteRewardEntity.class);
    private PendingRowSettler settler;

    @TempDir
    File dataFolder;

    @BeforeEach
    void setUp() {
        settler = new PendingRowSettler(repository, Logger.getLogger("test"), dataFolder);
        when(row.getId()).thenReturn(ROW_ID);
        when(row.getRewardData()).thenReturn(ROW_DATA);
        when(row.getPlayerUuid()).thenReturn(PLAYER);
        when(row.getServiceName()).thenReturn("TopG");
        when(repository.createAsync(any())).thenAnswer(call -> CompletableFuture.completedFuture(call.getArgument(0)));
    }

    @Test
    void untouchedRowStaysAsItIs() {
        settler.settle(row, ROW_DATA).join();

        verify(repository, never()).deleteAsync(any());
    }

    @Test
    void leftoversAreStoredAndThePaidRowRemoved() {
        when(repository.deleteAsync(ROW_ID)).thenReturn(CompletableFuture.completedFuture(null));

        settler.settle(row, LEFTOVER).join();

        verify(repository).createAsync(argThat(created -> LEFTOVER.equals(created.getRewardData())));
        assertEquals(List.of(row), settler.payable(PLAYER, List.of(row)).join());
    }

    @Test
    void paidRowThatCouldNotBeRemovedIsNeverPaidAgain() {
        when(repository.deleteAsync(ROW_ID)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db")));

        settler.settle(row, LEFTOVER).join();

        verify(repository).createAsync(argThat(created -> LEFTOVER.equals(created.getRewardData())));
        assertEquals(List.of(), settler.payable(PLAYER, List.of(row)).join(), "a paid but unremoved row was handed out");
        assertEquals(List.of(), settler.payable(PLAYER, List.of(row)).join(), "a failed retry released the row");

        when(repository.deleteAsync(ROW_ID)).thenReturn(CompletableFuture.completedFuture(null));
        assertEquals(List.of(), settler.payable(PLAYER, List.of(row)).join());
        verify(repository, times(4)).deleteAsync(ROW_ID);
        verify(repository, times(1)).createAsync(any());
    }

    @Test
    void leftoversThatCouldNotBeStoredAreKeptAndStoredOnTheNextDelivery() {
        when(repository.deleteAsync(ROW_ID)).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.createAsync(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db")));
        settler.settle(row, LEFTOVER).join();

        PendingRowSettler restarted = new PendingRowSettler(repository, Logger.getLogger("test"), dataFolder);
        when(repository.createAsync(any())).thenAnswer(call -> CompletableFuture.completedFuture(call.getArgument(0)));
        restarted.payable(PLAYER, List.of()).join();
        restarted.payable(PLAYER, List.of()).join();

        verify(repository, times(2)).createAsync(argThat(created -> LEFTOVER.equals(created.getRewardData())
                && "TopG".equals(created.getServiceName()) && PLAYER.equals(created.getPlayerUuid())));
    }

    @Test
    void unremovedRowStaysSkippedAfterARestart() {
        when(repository.deleteAsync(ROW_ID)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db")));
        settler.settle(row, null).join();

        PendingRowSettler restarted = new PendingRowSettler(repository, Logger.getLogger("test"), dataFolder);

        assertEquals(List.of(), restarted.payable(PLAYER, List.of(row)).join(), "a restart released a paid row");
    }
}
