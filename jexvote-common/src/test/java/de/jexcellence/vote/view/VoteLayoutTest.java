package de.jexcellence.vote.view;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VoteLayoutTest {

    @Test
    void fullBodyIsRowsOneToFourColumnsOneToSeven() {
        int[] body = VoteLayout.fullBody();
        assertEquals(28, body.length);
        assertEquals(10, body[0]);
        assertEquals(16, body[6]);
        assertEquals(19, body[7]);
        assertEquals(43, body[27]);
    }

    @Test
    void singleCardSitsInTheCentre() {
        assertArrayEquals(new int[]{22}, VoteLayout.centred(1));
    }

    @Test
    void oneRowOfListCardsIsCentredOnRowTwo() {
        assertArrayEquals(new int[]{21, 22, 23}, VoteLayout.centred(3));
        assertArrayEquals(new int[]{19, 20, 21, 22, 23, 24, 25}, VoteLayout.centred(7));
    }

    @Test
    void evenCountLeavesTheCentreColumnFree() {
        assertArrayEquals(new int[]{21, 23}, VoteLayout.centred(2));
        assertArrayEquals(new int[]{20, 21, 23, 24}, VoteLayout.centred(4));
    }

    @Test
    void cardsAreSplitIntoEvenRows() {
        int[] eight = VoteLayout.centred(8);
        assertArrayEquals(new int[]{20, 21, 23, 24, 29, 30, 32, 33}, eight);
    }

    @Test
    void fullPageEqualsFullBody() {
        assertArrayEquals(VoteLayout.fullBody(), VoteLayout.centred(28));
        assertEquals(28, VoteLayout.centred(40).length);
        assertEquals(0, VoteLayout.centred(0).length);
    }

    @Test
    void everyCentredLayoutStaysInsideTheBody() {
        for (int count = 1; count <= 28; count++) {
            int[] slots = VoteLayout.centred(count);
            assertEquals(count, slots.length);
            assertEquals(count, Arrays.stream(slots).distinct().count());
            for (int slot : slots) {
                int row = slot / 9;
                int column = slot % 9;
                assertEquals(true, row >= 1 && row <= 4 && column >= 1 && column <= 7, "slot " + slot);
            }
        }
    }

    @Test
    void siteBandUsesRowsTwoAndThree() {
        assertArrayEquals(new int[]{21, 23}, VoteLayout.centredInBand(2, 2, 2));
        int[] nine = VoteLayout.centredInBand(9, 2, 2);
        assertEquals(9, nine.length);
        assertEquals(2, nine[0] / 9);
        assertEquals(3, nine[8] / 9);
    }

    @Test
    void spacedRowKeepsAGapUpToFourCards() {
        assertArrayEquals(new int[]{11, 13, 15}, VoteLayout.spacedRow(3, 1));
        assertArrayEquals(new int[]{37, 39, 41, 43}, VoteLayout.spacedRow(4, 4));
        assertArrayEquals(new int[]{38, 39, 40, 41, 42}, VoteLayout.spacedRow(5, 4));
    }

    @Test
    void hubSplitsIntoTwoRowsAboveThree() {
        assertArrayEquals(new int[]{20, 22, 24}, VoteLayout.hub(3));
        assertArrayEquals(new int[]{21, 23, 30, 32}, VoteLayout.hub(4));
        assertArrayEquals(new int[]{20, 22, 24, 30, 32}, VoteLayout.hub(5));
    }

    @Test
    void pagesAreClamped() {
        assertEquals(1, VoteLayout.pageCount(0));
        assertEquals(2, VoteLayout.pageCount(29));
        assertEquals(0, VoteLayout.clampPage(-3, 2));
        assertEquals(1, VoteLayout.clampPage(9, 2));
        assertEquals(0, VoteLayout.clampPage(5, 0));
    }
}
