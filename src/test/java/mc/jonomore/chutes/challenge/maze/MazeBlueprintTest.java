package mc.jonomore.chutes.challenge.maze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import mc.jonomore.chutes.ChutesConfig;
import mc.jonomore.chutes.challenge.maze.MazeBlueprint.Kind;
import mc.jonomore.chutes.challenge.maze.MazeFloor.Cell;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class MazeBlueprintTest {

    private static MazeBlueprint blueprint(int cells, int floors, boolean ascending, long seed) {
        return new MazeBlueprint(new MazeGeometry(cells, floors, ascending),
                MazeGenerator.generate(cells, floors, seed));
    }

    private static boolean passable(Kind kind) {
        return kind == Kind.AIR || kind == Kind.LIGHT || kind == Kind.LADDER;
    }

    @Test
    void floorCountFollowsTheSpec() {
        ChutesConfig.Maze config = new ChutesConfig.Maze(2, 2, 6, 5, 16, 30);
        assertEquals(2, MazeChallenge.floorCount(1, config));
        assertEquals(2, MazeChallenge.floorCount(3, config));
        assertEquals(3, MazeChallenge.floorCount(5, config));
        assertEquals(6, MazeChallenge.floorCount(12, config));
        assertEquals(6, MazeChallenge.floorCount(40, config));
    }

    @Test
    void cellsAreClampedToTheArena() {
        int cells = MazeGeometry.maxCells(MazeChallenge.ARENA_FOOTPRINT);
        assertTrue(new MazeGeometry(cells, 1, true).width() <= MazeChallenge.ARENA_FOOTPRINT);
        assertTrue(new MazeGeometry(cells + 1, 1, true).width() > MazeChallenge.ARENA_FOOTPRINT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void structureIsSealed(boolean ascending) {
        MazeBlueprint maze = blueprint(9, 4, ascending, 3);
        MazeGeometry g = maze.geometry();
        int w = g.width();
        for (int dy = 0; dy < g.height(); dy++) {
            for (int i = 0; i < w; i++) {
                assertEquals(Kind.WALL, wallOrSlab(maze.kind(0, dy, i)), "west side");
                assertEquals(Kind.WALL, wallOrSlab(maze.kind(w - 1, dy, i)), "east side");
                assertEquals(Kind.WALL, wallOrSlab(maze.kind(i, dy, 0)), "north side");
                assertEquals(Kind.WALL, wallOrSlab(maze.kind(i, dy, w - 1)), "south side");
            }
        }
        for (int dx = 0; dx < w; dx++) {
            for (int dz = 0; dz < w; dz++) {
                assertTrue(solid(maze.kind(dx, 0, dz)), "bottom");
                assertTrue(solid(maze.kind(dx, g.height() - 1, dz)), "roof");
            }
        }
    }

    private static Kind wallOrSlab(Kind kind) {
        return kind == Kind.SLAB ? Kind.WALL : kind;
    }

    private static boolean solid(Kind kind) {
        return kind == Kind.SLAB || kind == Kind.GOAL || kind == Kind.WALL;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void spawnsAreFreeAndStandOnSolidGround(boolean ascending) {
        for (long seed = 0; seed < 20; seed++) {
            MazeBlueprint maze = blueprint(9, 5, ascending, seed);
            for (int t = 0; t < 5; t++) {
                int[] s = maze.spawnOffset(t);
                assertEquals(Kind.AIR, maze.kind(s[0], s[1], s[2]), "feet");
                assertEquals(Kind.AIR, maze.kind(s[0], s[1] + 1, s[2]), "head");
                assertTrue(solid(maze.kind(s[0], s[1] - 1, s[2])), "ground");
                assertEquals(maze.geometry().level(t), maze.geometry().levelAtFeet(s[1]));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void laddersAreSupportedAndJoinConsecutiveFloors(boolean ascending) {
        for (long seed = 0; seed < 20; seed++) {
            MazeBlueprint maze = blueprint(9, 5, ascending, seed);
            MazeGeometry g = maze.geometry();
            for (int dy = 0; dy < g.height(); dy++) {
                for (int dx = 0; dx < g.width(); dx++) {
                    for (int dz = 0; dz < g.width(); dz++) {
                        if (maze.kind(dx, dy, dz) == Kind.LADDER) {
                            Kind behind = maze.kind(dx + 1, dy, dz);
                            assertTrue(behind == Kind.PILLAR || behind == Kind.SLAB, "ladder faces west onto a solid block");
                        }
                    }
                }
            }
            for (int t = 0; t + 1 < 5; t++) {
                Cell exit = maze.floor(t).exit();
                int lower = Math.min(g.level(t), g.level(t + 1));
                int x = g.cellMin(exit.x());
                int z = g.cellMin(exit.z());
                for (int dy = g.slabY(lower) + 1; dy <= g.slabY(lower + 1); dy++) {
                    assertEquals(Kind.LADDER, maze.kind(x, dy, z), "ladder column at floor " + t + " exit");
                }
            }
        }
    }

    /** Walks the blueprint like a player: horizontal moves on each level, ladders between levels. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void goalIsReachableFromTheStart(boolean ascending) {
        for (long seed = 0; seed < 20; seed++) {
            MazeBlueprint maze = blueprint(9, 4, ascending, seed);
            MazeGeometry g = maze.geometry();
            int w = g.width();
            int h = g.height();
            boolean[][][] seen = new boolean[w][h][w];
            Deque<int[]> queue = new ArrayDeque<>();
            int[] start = maze.spawnOffset(0);
            queue.add(start);
            seen[start[0]][start[1]][start[2]] = true;
            boolean reached = false;
            while (!queue.isEmpty()) {
                int[] p = queue.poll();
                int level = g.levelAtFeet(p[1]);
                if (level == maze.goalLevel() && g.inCell(p[0], p[2], maze.goal())) {
                    reached = true;
                    break;
                }
                boolean onLadder = maze.kind(p[0], p[1], p[2]) == Kind.LADDER;
                for (int[] d : List.of(new int[] {1, 0, 0}, new int[] {-1, 0, 0}, new int[] {0, 0, 1},
                        new int[] {0, 0, -1}, new int[] {0, 1, 0}, new int[] {0, -1, 0})) {
                    int x = p[0] + d[0];
                    int y = p[1] + d[1];
                    int z = p[2] + d[2];
                    if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= w || seen[x][y][z]) {
                        continue;
                    }
                    Kind kind = maze.kind(x, y, z);
                    boolean vertical = d[1] != 0;
                    // Vertical moves only along ladders; horizontal moves only at the height of a player standing on a slab.
                    boolean ok = vertical
                            ? kind == Kind.LADDER || (onLadder && passable(kind))
                            : passable(kind) && y % MazeGeometry.LEVEL_HEIGHT == 1;
                    if (ok) {
                        seen[x][y][z] = true;
                        queue.add(new int[] {x, y, z});
                    }
                }
            }
            assertTrue(reached, "goal reachable, seed " + seed);
        }
    }
}
