package de.jexcellence.vote.view;

import org.jetbrains.annotations.NotNull;

/**
 * Slot math shared by every JExVote chest view (6 rows). The body is rows 1-4, columns 1-7 (28 slots). Small
 * content is centred instead of starting top-left, so a menu with three cards does not look half empty:
 * {@link #centred(int)} for lists (milestones, prizes, shop items), {@link #spacedRow(int, int)} for a row of
 * navigation or hub cards with a gap between them.
 *
 * @author JExcellence
 * @since 3.3.0
 */
public final class VoteLayout {

    /** Columns of one body row. */
    public static final int BODY_COLUMNS = 7;
    /** Rows of the body. */
    public static final int BODY_ROWS = 4;
    /** Slots of a full body page. */
    public static final int PAGE_SIZE = BODY_COLUMNS * BODY_ROWS;

    private static final int ROW_WIDTH = 9;
    private static final int CENTRE_COLUMN = 4;

    /**
     * Column sets for 1-7 cards in one row, always symmetric around the centre column. Contiguous cards,
     * except that an even count leaves the centre column free.
     */
    private static final int[][] COMPACT_COLUMNS = {
            {},
            {4},
            {3, 5},
            {3, 4, 5},
            {2, 3, 5, 6},
            {2, 3, 4, 5, 6},
            {1, 2, 3, 5, 6, 7},
            {1, 2, 3, 4, 5, 6, 7}
    };

    /** Column sets for 1-4 cards with one empty column between them. */
    private static final int[][] SPACED_COLUMNS = {
            {},
            {4},
            {3, 5},
            {2, 4, 6},
            {1, 3, 5, 7}
    };

    private VoteLayout() {
    }

    /** @return the 28 body slots (rows 1-4, columns 1-7) in reading order. */
    public static int @NotNull [] fullBody() {
        int[] slots = new int[PAGE_SIZE];
        int index = 0;
        for (int row = 1; row <= BODY_ROWS; row++) {
            for (int column = 1; column <= BODY_COLUMNS; column++) {
                slots[index++] = row * ROW_WIDTH + column;
            }
        }
        return slots;
    }

    /**
     * Slots for {@code count} list cards in the body, centred horizontally and vertically. The cards are split
     * into as few rows as possible with the rows as even as possible (8 cards become 4 + 4, not 7 + 1). A full
     * page returns {@link #fullBody()}.
     *
     * @param count how many cards, clamped to 0-28
     * @return the slots in reading order
     */
    public static int @NotNull [] centred(int count) {
        int cards = Math.clamp(count, 0, PAGE_SIZE);
        if (cards == 0) {
            return new int[0];
        }
        int rows = (cards + BODY_COLUMNS - 1) / BODY_COLUMNS;
        int firstRow = 1 + (BODY_ROWS - rows) / 2;
        return distribute(cards, rows, firstRow);
    }

    /**
     * Slots for {@code count} cards inside a band of body rows, centred like {@link #centred(int)}. Used by the
     * vote menu, where the sites live in rows 2-3 only.
     *
     * @param count    how many cards
     * @param firstRow first row of the band
     * @param rows     height of the band in rows
     * @return the slots in reading order
     */
    public static int @NotNull [] centredInBand(int count, int firstRow, int rows) {
        int cards = Math.clamp(count, 0, rows * BODY_COLUMNS);
        if (cards == 0) {
            return new int[0];
        }
        int used = (cards + BODY_COLUMNS - 1) / BODY_COLUMNS;
        return distribute(cards, used, firstRow);
    }

    private static int @NotNull [] distribute(int cards, int rows, int firstRow) {
        int perRow = (cards + rows - 1) / rows;
        int[] slots = new int[cards];
        int placed = 0;
        for (int row = 0; row < rows; row++) {
            int inRow = Math.min(perRow, cards - placed);
            for (int column : COMPACT_COLUMNS[inRow]) {
                slots[placed++] = (firstRow + row) * ROW_WIDTH + column;
            }
        }
        return slots;
    }

    /**
     * Slots for up to seven cards in one row with a free column between them where they fit (up to four);
     * five to seven cards sit side by side, still centred.
     *
     * @param count how many cards, clamped to 0-7
     * @param row   the inventory row (0-5)
     * @return the slots from left to right
     */
    public static int @NotNull [] spacedRow(int count, int row) {
        int cards = Math.clamp(count, 0, BODY_COLUMNS);
        int[] columns = cards < SPACED_COLUMNS.length ? SPACED_COLUMNS[cards] : COMPACT_COLUMNS[cards];
        int[] slots = new int[columns.length];
        for (int i = 0; i < columns.length; i++) {
            slots[i] = row * ROW_WIDTH + columns[i];
        }
        return slots;
    }

    /**
     * Slots for hub cards (a handful of feature cards): up to three in row 2, more split over rows 2 and 3,
     * each row spaced and centred.
     *
     * @param count how many cards, clamped to 0-8
     * @return the slots in reading order
     */
    public static int @NotNull [] hub(int count) {
        int cards = Math.clamp(count, 0, 2 * SPACED_COLUMNS.length - 2);
        if (cards <= 3) {
            return spacedRow(cards, 2);
        }
        int top = (cards + 1) / 2;
        int[] first = spacedRow(top, 2);
        int[] second = spacedRow(cards - top, 3);
        int[] slots = new int[cards];
        System.arraycopy(first, 0, slots, 0, first.length);
        System.arraycopy(second, 0, slots, first.length, second.length);
        return slots;
    }

    /** @return the page count for {@code entries} body cards (at least one). */
    public static int pageCount(int entries) {
        return Math.max(1, (entries + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** @return {@code page} clamped into {@code [0, pages)}. */
    public static int clampPage(int page, int pages) {
        return Math.clamp(page, 0, Math.max(0, pages - 1));
    }

    /** @return the centre slot of the body (row 2, column 4), for a single notice card. */
    public static int centreSlot() {
        return 2 * ROW_WIDTH + CENTRE_COLUMN;
    }
}
