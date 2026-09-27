package de.jexcellence.vote.command;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandTreeMergerTest {

    private static final List<String> BUNDLED = List.of(
            "name: vote",
            "subcommands:",
            "  - name: help",
            "    description: \"Help\"",
            "",
            "  - name: streak",
            "    description: \"Streak\"",
            "    aliases:",
            "      - streaks",
            "",
            "  - name: shop",
            "    description: \"Shop\"",
            "default: true");

    @Test
    void addsOnlyMissingEntriesBeforeTheNextTopLevelKey() {
        List<String> live = new ArrayList<>(List.of(
                "name: vote",
                "subcommands:",
                "  - name: help",
                "    description: \"Owner text\"",
                "",
                "  - name: shop",
                "    description: \"Shop\"",
                "default: true"));

        List<String> added = CommandTreeMerger.merge(BUNDLED, live);

        assertEquals(List.of("streak"), added);
        assertEquals("    description: \"Owner text\"", live.get(3));
        int streak = live.indexOf("  - name: streak");
        assertTrue(streak > live.indexOf("  - name: shop"));
        assertEquals("      - streaks", live.get(streak + 3));
        assertEquals("default: true", live.get(live.size() - 1));
    }

    @Test
    void leavesAnUpToDateFileAlone() {
        List<String> live = new ArrayList<>(BUNDLED);

        assertTrue(CommandTreeMerger.merge(BUNDLED, live).isEmpty());
        assertEquals(BUNDLED, live);
    }
}
