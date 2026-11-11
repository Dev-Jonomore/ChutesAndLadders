package mc.jonomore.chutes.challenge.maze;

import java.util.List;
import mc.jonomore.chutes.challenge.maze.MazeFloor.Cell;
import mc.jonomore.chutes.challenge.maze.MazeFloor.Direction;

/**
 * What goes at every block offset of a built maze, free of Bukkit types.
 *
 * <p>Floors connect at the exit cell of the earlier travel floor. In that cell's lower level a
 * pillar stands at the cell's {@code (+1, 0)} interior block with a west-facing ladder on it at
 * {@code (0, 0)}; the ladder continues through the hole in the slab above, where the slab itself
 * holds it. Ladders work in adventure mode both for climbing up and for sliding down.
 */
public final class MazeBlueprint {

    public enum Kind {
        AIR,
        /** Floor, ceiling and roof slabs. */
        SLAB,
        WALL,
        /** Support behind the ladder in the lower level of a connection. */
        PILLAR,
        /** Ladder facing west, attached to the block at {@code +x}. */
        LADDER,
        /** Invisible light source. */
        LIGHT,
        /** Finish pad in the floor of the goal cell. */
        GOAL
    }

    private final MazeGeometry geometry;
    private final List<MazeFloor> floors;
    /** connectionAbove[level]: the cell whose ladder leads from this level to the one above, or null. */
    private final Cell[] connectionAbove;
    private final int goalLevel;
    private final Cell goal;

    public MazeBlueprint(MazeGeometry geometry, List<MazeFloor> floors) {
        if (floors.size() != geometry.floors()) {
            throw new IllegalArgumentException("geometry is for " + geometry.floors() + " floors, got " + floors.size());
        }
        this.geometry = geometry;
        this.floors = List.copyOf(floors);
        this.connectionAbove = new Cell[geometry.floors()];
        for (int travel = 0; travel + 1 < floors.size(); travel++) {
            int lower = Math.min(geometry.level(travel), geometry.level(travel + 1));
            connectionAbove[lower] = floors.get(travel).exit();
        }
        int lastTravel = floors.size() - 1;
        this.goalLevel = geometry.level(lastTravel);
        this.goal = floors.get(lastTravel).exit();
    }

    public MazeGeometry geometry() {
        return geometry;
    }

    public List<MazeFloor> floors() {
        return floors;
    }

    public MazeFloor floor(int travelFloor) {
        return floors.get(travelFloor);
    }

    public Cell goal() {
        return goal;
    }

    public int goalLevel() {
        return goalLevel;
    }

    /** Offset of the block a player arriving on {@code travelFloor} should stand in: never a wall, ladder or hole. */
    public int[] spawnOffset(int travelFloor) {
        Cell start = floors.get(travelFloor).start();
        return new int[] {
            geometry.cellMin(start.x()) + 1,
            geometry.slabY(geometry.level(travelFloor)) + 1,
            geometry.cellMin(start.z()) + 1
        };
    }

    /** A direction out of the start cell of {@code travelFloor} that has a passage, for spawn facing. */
    public Direction openingAtStart(int travelFloor) {
        MazeFloor floor = floors.get(travelFloor);
        for (Direction direction : Direction.values()) {
            if (floor.isOpen(floor.start(), direction)) {
                return direction;
            }
        }
        return Direction.SOUTH;
    }

    public Kind kind(int dx, int dy, int dz) {
        int width = geometry.width();
        if (dx < 0 || dz < 0 || dx >= width || dz >= width || dy < 0 || dy >= geometry.height()) {
            return Kind.AIR;
        }
        int level = dy / MazeGeometry.LEVEL_HEIGHT;
        int layer = dy % MazeGeometry.LEVEL_HEIGHT;

        if (layer == 0) {
            if (level > 0) {
                Cell below = connectionAbove[level - 1];
                if (below != null && isLadderColumn(dx, dz, below)) {
                    return Kind.LADDER;
                }
            }
            if (level == goalLevel && geometry.inCell(dx, dz, goal)) {
                return Kind.GOAL;
            }
            return Kind.SLAB;
        }

        if (isWall(level, dx, dz)) {
            return Kind.WALL;
        }
        Cell connection = connectionAbove[level];
        if (connection != null) {
            if (isLadderColumn(dx, dz, connection)) {
                return Kind.LADDER;
            }
            if (dx == geometry.cellMin(connection.x()) + 1 && dz == geometry.cellMin(connection.z())) {
                return Kind.PILLAR;
            }
        }
        if (layer == MazeGeometry.AIR_LAYERS && dx % MazeGeometry.PITCH == 2 && dz % MazeGeometry.PITCH == 2) {
            return Kind.LIGHT;
        }
        return Kind.AIR;
    }

    private boolean isLadderColumn(int dx, int dz, Cell cell) {
        return dx == geometry.cellMin(cell.x()) && dz == geometry.cellMin(cell.z());
    }

    private boolean isWall(int level, int dx, int dz) {
        boolean xLine = dx % MazeGeometry.PITCH == 0;
        boolean zLine = dz % MazeGeometry.PITCH == 0;
        if (!xLine && !zLine) {
            return false;
        }
        if (xLine && zLine) {
            return true;
        }
        MazeFloor floor = floors.get(geometry.travelFloor(level));
        if (xLine) {
            // Wall segment between cell (dx/3 - 1, z) and (dx/3, z); the outer walls are never open.
            Cell west = new Cell(dx / MazeGeometry.PITCH - 1, dz / MazeGeometry.PITCH);
            return !floor.isOpen(west, Direction.EAST);
        }
        Cell north = new Cell(dx / MazeGeometry.PITCH, dz / MazeGeometry.PITCH - 1);
        return !floor.isOpen(north, Direction.SOUTH);
    }
}
