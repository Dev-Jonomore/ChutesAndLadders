package mc.jonomore.chutes.challenge.maze;

/**
 * Block layout of a maze relative to the arena origin, free of Bukkit types.
 *
 * <p>Horizontally, cell {@code i} occupies offsets {@code 3i+1..3i+2} with walls on multiples of 3,
 * so a {@code cells}-wide maze is {@code 3 * cells + 1} blocks wide. Vertically each physical level
 * {@code L} is a solid slab at {@code 4L} with three air layers above it; the slab of level
 * {@code L + 1} is the ceiling of level {@code L}, and a roof slab sits at {@code 4 * floors}.
 *
 * <p>Travel floor {@code t} (the order players visit floors in) is physical level {@code t} when
 * ascending (ladder) and {@code floors - 1 - t} when descending (chute).
 */
public record MazeGeometry(int cells, int floors, boolean ascending) {

    public static final int PITCH = 3;
    public static final int LEVEL_HEIGHT = 4;
    public static final int AIR_LAYERS = LEVEL_HEIGHT - 1;

    /** Largest cell count whose maze fits in a square footprint of {@code footprint} blocks. */
    public static int maxCells(int footprint) {
        return (footprint - 1) / PITCH;
    }

    /** Footprint width and depth in blocks. */
    public int width() {
        return cells * PITCH + 1;
    }

    /** Height in blocks, bottom slab to roof inclusive. */
    public int height() {
        return floors * LEVEL_HEIGHT + 1;
    }

    public int level(int travelFloor) {
        return ascending ? travelFloor : floors - 1 - travelFloor;
    }

    public int travelFloor(int level) {
        return ascending ? level : floors - 1 - level;
    }

    public int slabY(int level) {
        return level * LEVEL_HEIGHT;
    }

    /** Offset of the lower of the two interior blocks of cell index {@code i} along x or z. */
    public int cellMin(int index) {
        return index * PITCH + 1;
    }

    public boolean inFootprint(int dx, int dz) {
        return dx >= 0 && dz >= 0 && dx < width() && dz < width();
    }

    /**
     * The level whose walkable air a player with feet block offset {@code dy} is standing in,
     * or -1 when the feet are inside a slab (climbing between levels) or outside the maze.
     */
    public int levelAtFeet(int dy) {
        if (dy < 1 || dy >= floors * LEVEL_HEIGHT) {
            return -1;
        }
        int above = dy - 1;
        return above % LEVEL_HEIGHT < AIR_LAYERS ? above / LEVEL_HEIGHT : -1;
    }

    /** Whether offset {@code (dx, dy, dz)} is inside the maze's interior, above the bottom slab and below the roof. */
    public boolean inside(int dx, int dy, int dz) {
        return inFootprint(dx, dz) && dy >= 1 && dy < floors * LEVEL_HEIGHT;
    }

    /** Whether offset {@code (dx, dz)} lies in the 2 x 2 interior of {@code cell}. */
    public boolean inCell(int dx, int dz, MazeFloor.Cell cell) {
        int x = cellMin(cell.x());
        int z = cellMin(cell.z());
        return dx >= x && dx <= x + 1 && dz >= z && dz <= z + 1;
    }
}
