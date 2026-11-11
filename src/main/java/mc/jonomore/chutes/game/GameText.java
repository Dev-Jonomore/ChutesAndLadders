package mc.jonomore.chutes.game;

import static net.kyori.adventure.text.Component.text;

import java.time.Duration;
import mc.jonomore.chutes.board.BoardRoom;
import mc.jonomore.chutes.board.ChallengeType;
import mc.jonomore.chutes.board.Transit;
import mc.jonomore.chutes.challenge.ChallengeResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

/** Player-facing text. Rooms are shown 1-based ("Room 1 / 50"); the finish is room N. */
final class GameText {

    private static final Component PREFIX = text("[Chutes] ", NamedTextColor.GOLD);
    private static final Title.Times TIMES = Title.Times.times(
            Duration.ofMillis(250), Duration.ofMillis(2000), Duration.ofMillis(500));

    private GameText() {
    }

    static Component info(String message) {
        return PREFIX.append(text(message, NamedTextColor.YELLOW));
    }

    static Component info(Component message) {
        return PREFIX.append(message.colorIfAbsent(NamedTextColor.YELLOW));
    }

    static Component error(String message) {
        return PREFIX.append(text(message, NamedTextColor.RED));
    }

    static String challengeName(ChallengeType type) {
        return switch (type) {
            case MAZE -> "maze";
            case WAVES -> "waves";
        };
    }

    static String transitLabel(Transit transit) {
        return transit.isLadder() ? "Ladder +" + transit.length() : "Chute -" + transit.length();
    }

    static Title roomTitle(BoardRoom room, int boardSize, Component note) {
        Component main = text("Room " + (room.index() + 1) + " / " + boardSize, NamedTextColor.WHITE);
        Component kind = switch (room.kind()) {
            case NORMAL -> text("Find the button", NamedTextColor.GRAY);
            case BREAK -> text("Rest stop: healed, checkpoint saved", NamedTextColor.GREEN);
            case LADDER, CHUTE -> {
                Transit transit = room.transit();
                NamedTextColor color = transit != null && transit.isLadder() ? NamedTextColor.AQUA : NamedTextColor.RED;
                String label = transit == null ? room.kind().name() : transitLabel(transit)
                        + " (" + challengeName(transit.challenge()) + ")";
                yield text(label, color, TextDecoration.BOLD);
            }
        };
        Component subtitle = note.equals(Component.empty()) ? kind : note.append(text(" · ", NamedTextColor.DARK_GRAY)).append(kind);
        return Title.title(main, subtitle, TIMES);
    }

    static Title transitStartTitle(Transit transit, ChallengeType type) {
        NamedTextColor color = transit.isLadder() ? NamedTextColor.AQUA : NamedTextColor.RED;
        String goal = switch (type) {
            case MAZE -> transit.isLadder() ? "Climb the maze!" : "Escape down the maze!";
            case WAVES -> "Defeat every wave!";
        };
        return Title.title(text(transitLabel(transit), color, TextDecoration.BOLD), text(goal, NamedTextColor.YELLOW), TIMES);
    }

    static Component transitOutcome(Transit transit, ChallengeResult result, int actualMoved) {
        int rooms = Math.abs(actualMoved);
        String verb = transit.isLadder() ? "climbed" : "slid";
        String outcome = result.completed() ? "completed" : "failed";
        NamedTextColor color = transit.isLadder() ? NamedTextColor.AQUA : NamedTextColor.RED;
        return text((transit.isLadder() ? "Ladder: " : "Chute: ") + verb + " " + rooms + " of " + transit.length()
                + " room" + (transit.length() == 1 ? "" : "s") + " (" + outcome + ")", color);
    }

    static Title finishTitle(int placement) {
        Component main = placement == 1
                ? text("You win!", NamedTextColor.GOLD, TextDecoration.BOLD)
                : text("Finished #" + placement, NamedTextColor.GREEN, TextDecoration.BOLD);
        return Title.title(main, text("Wait here until the game ends", NamedTextColor.GRAY), TIMES);
    }

    static String placementSuffix(int placement) {
        return placement == 1 ? " and wins the game!" : " in place #" + placement + ".";
    }
}
