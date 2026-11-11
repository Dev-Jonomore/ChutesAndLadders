package mc.jonomore.chutes.challenge.maze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import mc.jonomore.chutes.challenge.maze.MazeFloor.Cell;
import mc.jonomore.chutes.challenge.maze.MazeFloor.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MazeGeneratorTest {

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 9, 17, 42})
    void everyFloorIsAPerfectMaze(int cells) {
        for (long seed = 0; seed < 20; seed++) {
            for (MazeFloor floor : MazeGenerator.generate(cells, 4, seed)) {
                assertEquals(cells * cells - 1, floor.passageCount(), "a spanning tree has n - 1 edges");
                int[][] distance = floor.distancesFrom(new Cell(0, 0));
                for (int[] column : distance) {
                    for (int d : column) {
                        assertTrue(d >= 0, "every cell is reachable");
                    }
                }
            }
        }
    }

    @Test
    void outerWallsAreClosed() {
        MazeFloor floor = MazeGenerator.generate(9, 1, 7).getFirst();
        for (int i = 0; i < 9; i++) {
            assertTrue(!floor.isOpen(new Cell(i, 0), Direction.NORTH));
            assertTrue(!floor.isOpen(new Cell(i, 8), Direction.SOUTH));
            assertTrue(!floor.isOpen(new Cell(0, i), Direction.WEST));
            assertTrue(!floor.isOpen(new Cell(8, i), Direction.EAST));
        }
    }

    @Test
    void exitOfEachFloorIsStartOfTheNext() {
        for (long seed = 0; seed < 50; seed++) {
            List<MazeFloor> floors = MazeGenerator.generate(9, 6, seed);
            for (int k = 0; k + 1 < floors.size(); k++) {
                assertEquals(floors.get(k).exit(), floors.get(k + 1).start());
            }
        }
    }

    @Test
    void exitIsTheFarthestCellFromTheStart() {
        for (long seed = 0; seed < 50; seed++) {
            for (MazeFloor floor : MazeGenerator.generate(9, 3, seed)) {
                assertNotEquals(floor.start(), floor.exit());
                int[][] distance = floor.distancesFrom(floor.start());
                int max = 0;
                for (int[] column : distance) {
                    for (int d : column) {
                        max = Math.max(max, d);
                    }
                }
                assertEquals(max, distance[floor.exit().x()][floor.exit().z()]);
            }
        }
    }

    @Test
    void sameSeedGivesTheSameMaze() {
        List<MazeFloor> a = MazeGenerator.generate(9, 5, 12345L);
        List<MazeFloor> b = MazeGenerator.generate(9, 5, 12345L);
        assertEquals(describe(a), describe(b));
    }

    @Test
    void differentSeedsGiveDifferentMazes() {
        assertNotEquals(describe(MazeGenerator.generate(9, 3, 1L)), describe(MazeGenerator.generate(9, 3, 2L)));
    }

    private static String describe(List<MazeFloor> floors) {
        StringBuilder out = new StringBuilder();
        for (MazeFloor floor : floors) {
            out.append(floor.start()).append(floor.exit());
            for (int x = 0; x < floor.cells(); x++) {
                for (int z = 0; z < floor.cells(); z++) {
                    Cell cell = new Cell(x, z);
                    out.append(floor.isOpen(cell, Direction.EAST) ? 'e' : '.');
                    out.append(floor.isOpen(cell, Direction.SOUTH) ? 's' : '.');
                }
            }
            out.append('|');
        }
        return out.toString();
    }
}
