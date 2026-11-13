package de.jexcellence.vote.service;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The console commands a vote runs besides its rewards: the daily-reward commands of the first vote of the day and
 * the streak bonus commands of a streak day. {@code {player}} stays in the commands; it is filled in when they run.
 *
 * @author JExcellence
 * @since 3.2.11
 */
final class VoteCommandSet {

    private final List<String> dailyCommands;
    private final AtomicReference<Map<Integer, List<String>>> streakCommands;

    VoteCommandSet(@NotNull List<String> dailyCommands, @NotNull Map<Integer, List<String>> streakCommands) {
        this.dailyCommands = List.copyOf(dailyCommands);
        this.streakCommands = new AtomicReference<>(streakCommands);
    }

    void setStreakCommands(@NotNull Map<Integer, List<String>> commands) {
        streakCommands.set(commands);
    }

    @NotNull List<String> daily() {
        return dailyCommands;
    }

    /** The streak commands of {@code streak} with service and streak filled in. */
    @NotNull List<String> streakFor(@NotNull String serviceName, int streak) {
        List<String> commands = streakCommands.get().get(streak);
        return commands == null ? List.of() : fill(commands, serviceName, streak);
    }

    /** Fills {@code {service}} and {@code {streak}} into vote commands; {@code {player}} stays for the run. */
    static @NotNull List<String> fill(@NotNull List<String> commands, @NotNull String serviceName, int streak) {
        String streakText = String.valueOf(streak);
        return commands.stream()
                .map(command -> command.replace("{service}", serviceName).replace("{streak}", streakText))
                .toList();
    }

    /**
     * The commands a queued vote replays on the next join: the daily bonus on the first vote of the day and the
     * streak bonus commands, the same commands an online vote runs right away.
     */
    @NotNull List<String> queuedFor(@NotNull String serviceName, int streak, boolean firstDailyBonus) {
        List<String> commands = new ArrayList<>(firstDailyBonus ? dailyCommands : List.of());
        commands.addAll(streakFor(serviceName, streak));
        return commands;
    }
}
