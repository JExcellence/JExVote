package de.jexcellence.vote.database.repository;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimedStreakRewardRepositoryTest {

    @Test
    void duplicateClaimWrappedByTheTransactionLayerCountsAsWritten() {
        Exception wrapped = new IllegalStateException("Persistence operation failed",
                new RuntimeException("could not execute statement",
                        new SQLException("duplicate key value violates unique constraint", "23505")));

        assertTrue(ClaimedStreakRewardRepository.isDuplicateKey(wrapped));
    }

    @Test
    void mysqlDuplicateKeyCountsAsWritten() {
        assertTrue(ClaimedStreakRewardRepository.isDuplicateKey(
                new IllegalStateException("failed", new SQLException("Duplicate entry", "23000", 1062))));
    }

    @Test
    void otherConstraintErrorsAreNotMistakenForAnExistingClaim() {
        assertFalse(ClaimedStreakRewardRepository.isDuplicateKey(new SQLException("null value", "23502")));
        assertFalse(ClaimedStreakRewardRepository.isDuplicateKey(new SQLException("foreign key", "23000", 1452)));
    }

    @Test
    void lostConnectionIsNotMistakenForAnExistingClaim() {
        Exception wrapped = new IllegalStateException("Persistence operation failed",
                new SQLException("connection closed", "08006"));

        assertFalse(ClaimedStreakRewardRepository.isDuplicateKey(wrapped));
    }

    @Test
    void failureWithoutSqlStateIsNotMistakenForAnExistingClaim() {
        assertFalse(ClaimedStreakRewardRepository.isDuplicateKey(new IllegalStateException("pool exhausted")));
        assertFalse(ClaimedStreakRewardRepository.isDuplicateKey(new SQLException("no state")));
    }
}
