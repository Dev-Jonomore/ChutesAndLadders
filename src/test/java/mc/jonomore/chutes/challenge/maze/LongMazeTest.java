package mc.jonomore.chutes.challenge.maze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import mc.jonomore.chutes.challenge.maze.MazeBlueprint.Kind;
import mc.jonomore.chutes.challenge.maze.MazeFloor.Cell;
import org.junit.jupiter.api.Test;

/** Mazes that are longer than they are wide, as used for ladder and chute challenges. */
class LongMazeTest {

    private static final int WIDE = 5;
    private static final int LONG = 16;

    @Test
    void everyFloorIsAPerfectRectangularMaze() {
        for (long seed = 0; seed < 50; seed++) {
            List<MazeFloor> floors = MazeGenerator.generate(WIDE, LONG, 4, seed);
            for (int k = 0; k < floors.size(); k++) {
                MazeFloor floor = floors.get(k);
                assertEquals(WIDE, floor.cellsX());
                assertEquals(LONG, floor.cellsZ());
                assertEquals(WIDE * LONG - 1, floor.passageCount());
                int[][] distance = floor.distancesFrom(floor.start());
                for (int x = 0; x < WIDE; x++) {
                    for (int z = 0; z < LONG; z++) {
                        assertTrue(distance[x][z] >= 0, "cell " + new Cell(x, z) + " unreachable");
                    }
                }
                if (k + 1 < floors.size()) {
                    assertEquals(floor.exit(), floors.get(k + 1).start());
                }
            }
        }
    }

    @Test
    void geometryIsLongAndSealed() {
        MazeGeometry geometry = new MazeGeometry(WIDE, LONG, 3, true);
        assertEquals(WIDE * MazeGeometry.PITCH + 1, geometry.width());
        assertEquals(LONG * MazeGeometry.PITCH + 1, geometry.depth());
        assertTrue(geometry.depth() >= 3 * geometry.width());

        MazeBlueprint maze = new MazeBlueprint(geometry, MazeGenerator.generate(WIDE, LONG, 3, 11));
        for (int dy = 0; dy < geometry.height(); dy++) {
            for (int dx = 0; dx < geometry.width(); dx++) {
                assertNotEquals(Kind.AIR, maze.kind(dx, dy, 0), "north side");
                assertNotEquals(Kind.AIR, maze.kind(dx, dy, geometry.depth() - 1), "south side");
            }
            for (int dz = 0; dz < geometry.depth(); dz++) {
                assertNotEquals(Kind.AIR, maze.kind(0, dy, dz), "west side");
                assertNotEquals(Kind.AIR, maze.kind(geometry.width() - 1, dy, dz), "east side");
            }
        }
        // Nothing is placed past the long edge, and the far cells along z are real maze, not padding.
        assertEquals(Kind.AIR, maze.kind(1, 1, geometry.depth()));
        assertTrue(geometry.inFootprint(geometry.width() - 1, geometry.depth() - 1));
    }
}
