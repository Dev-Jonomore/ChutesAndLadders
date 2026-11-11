package mc.jonomore.chutes.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import mc.jonomore.chutes.ChutesConfig.BoardSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RandomBoardGeneratorTest {

    /** Matches the shipped config.yml. */
    private static final BoardSettings DEFAULTS =
            new BoardSettings(50, 6, 10, true, 3, 0.12, 0.12, 3, 12, 0.5);

    private static final int SEEDS = 300;

    private final RandomBoardGenerator generator = new RandomBoardGenerator();

    static Stream<Arguments> boards() {
        BoardSettings dense = new BoardSettings(50, 6, 10, true, 3, 1.0, 1.0, 1, 30, 0.5);
        BoardSettings tinyRoll = new BoardSettings(50, 2, 2, false, 1, 0.5, 0.5, 1, 4, 0.0);
        BoardSettings rollOne = new BoardSettings(50, 1, 3, false, 1, 0.3, 0.5, 1, 5, 1.0);
        BoardSettings longTransits = new BoardSettings(50, 6, 10, true, 3, 0.2, 0.2, 20, 40, 0.5);
        BoardSettings bigSafeStart = new BoardSettings(50, 6, 4, true, 100, 0.3, 0.3, 1, 5, 0.5);
        BoardSettings none = new BoardSettings(50, 6, 10, true, 3, 0.0, 0.0, 3, 12, 0.5);
        return Stream.of(
                Arguments.of("defaults/5", DEFAULTS, 5),
                Arguments.of("defaults/8", DEFAULTS, 8),
                Arguments.of("defaults/20", DEFAULTS, 20),
                Arguments.of("defaults/50", DEFAULTS, 50),
                Arguments.of("defaults/200", DEFAULTS, 200),
                Arguments.of("dense/5", dense, 5),
                Arguments.of("dense/30", dense, 30),
                Arguments.of("dense/120", dense, 120),
                Arguments.of("tinyRoll/40", tinyRoll, 40),
                Arguments.of("rollOne/40", rollOne, 40),
                Arguments.of("longTransits/10", longTransits, 10),
                Arguments.of("longTransits/100", longTransits, 100),
                Arguments.of("bigSafeStart/50", bigSafeStart, 50),
                Arguments.of("none/50", none, 50));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boards")
    void invariantsHoldAcrossSeeds(String label, BoardSettings settings, int rooms) {
        for (long seed = 0; seed < SEEDS; seed++) {
            Board board = generator.generate(settings, rooms, seed * 7919 - 1000);
            assertValid(settings, rooms, board, label + " seed " + seed);
        }
    }

    @Test
    void sameInputsGiveSameBoard() {
        for (long seed = -50; seed < 50; seed++) {
            assertEquals(
                    generator.generate(DEFAULTS, 60, seed),
                    new RandomBoardGenerator().generate(DEFAULTS, 60, seed));
        }
    }

    @Test
    void differentSeedsGiveDifferentBoards() {
        assertNotEquals(
                generator.generate(DEFAULTS, 60, 1).rooms(),
                generator.generate(DEFAULTS, 60, 2).rooms());
    }

    @Test
    void boardCarriesSeed() {
        assertEquals(1234L, generator.generate(DEFAULTS, 20, 1234L).seed());
    }

    @Test
    void densitiesAreRoughlyMetOnRoomyBoards() {
        int ladders = 0;
        int chutes = 0;
        int boards = 100;
        for (long seed = 0; seed < boards; seed++) {
            Board board = generator.generate(DEFAULTS, 100, seed);
            ladders += count(board, RoomKind.LADDER);
            chutes += count(board, RoomKind.CHUTE);
        }
        // 0.12 * 100 = 12 each per board.
        assertTrue(ladders / (double) boards > 10, "average ladders " + ladders / (double) boards);
        assertTrue(chutes / (double) boards > 10, "average chutes " + chutes / (double) boards);
        assertTrue(ladders / (double) boards <= 12);
        assertTrue(chutes / (double) boards <= 12);
    }

    @Test
    void mazeWeightZeroAndOne() {
        BoardSettings allWaves = new BoardSettings(50, 6, 10, true, 3, 0.2, 0.2, 2, 8, 0.0);
        BoardSettings allMazes = new BoardSettings(50, 6, 10, true, 3, 0.2, 0.2, 2, 8, 1.0);
        for (long seed = 0; seed < 50; seed++) {
            assertTrue(transits(generator.generate(allWaves, 80, seed)).stream()
                    .allMatch(t -> t.challenge() == ChallengeType.WAVES));
            assertTrue(transits(generator.generate(allMazes, 80, seed)).stream()
                    .allMatch(t -> t.challenge() == ChallengeType.MAZE));
        }
    }

    @Test
    void mazeWeightHalfMixesTypes() {
        long mazes = 0;
        long total = 0;
        for (long seed = 0; seed < 100; seed++) {
            List<Transit> transits = transits(generator.generate(DEFAULTS, 100, seed));
            total += transits.size();
            mazes += transits.stream().filter(t -> t.challenge() == ChallengeType.MAZE).count();
        }
        double share = mazes / (double) total;
        assertTrue(share > 0.4 && share < 0.6, "maze share " + share);
    }

    @Test
    void rejectsTooFewRooms() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(DEFAULTS, 4, 0));
    }

    private static void assertValid(BoardSettings settings, int n, Board board, String context) {
        assertEquals(n, board.size(), context);
        assertEquals(RoomKind.NORMAL, board.room(0).kind(), context + ": room 0");
        int chuteRun = 0;
        for (int i = 0; i < n; i++) {
            BoardRoom room = board.room(i);
            String where = context + ": room " + i;
            assertEquals(i, room.index(), where);

            boolean shouldBreak = i > 0 && i % settings.breakInterval() == 0;
            assertEquals(shouldBreak, room.kind() == RoomKind.BREAK, where + " break placement");

            boolean isTransit = room.kind() == RoomKind.LADDER || room.kind() == RoomKind.CHUTE;
            if (!isTransit) {
                assertNull(room.transit(), where);
            } else {
                Transit transit = room.transit();
                assertNotNull(transit, where);
                assertTrue(i >= settings.safeStart(), where + " inside safe start");
                assertEquals(room.kind() == RoomKind.LADDER, transit.isLadder(), where + " delta sign");
                assertTrue(transit.length() >= settings.minLength() && transit.length() <= settings.maxLength(),
                        where + " length " + transit.length());
                int target = i + transit.delta();
                assertTrue(target >= 0 && target <= n - 1, where + " target " + target);
                RoomKind targetKind = board.room(target).kind();
                assertTrue(targetKind == RoomKind.NORMAL || targetKind == RoomKind.BREAK,
                        where + " lands on " + targetKind);
            }

            chuteRun = room.kind() == RoomKind.CHUTE ? chuteRun + 1 : 0;
            assertTrue(chuteRun <= settings.maxRoll() - 1, where + " chute run " + chuteRun);
        }
    }

    private static int count(Board board, RoomKind kind) {
        return (int) board.rooms().stream().filter(room -> room.kind() == kind).count();
    }

    private static List<Transit> transits(Board board) {
        return board.rooms().stream().map(BoardRoom::transit).filter(t -> t != null).toList();
    }
}
