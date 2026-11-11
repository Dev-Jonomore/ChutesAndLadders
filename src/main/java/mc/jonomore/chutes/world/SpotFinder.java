package mc.jonomore.chutes.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;

/**
 * Geometry analysis for built-in templates: which cells a player can walk to from the entrance,
 * which button spots they can click from there, and a spread-out pick of such spots.
 */
final class SpotFinder {

    /** Eye height of a standing player. */
    static final double EYE_HEIGHT = 1.62;
    /**
     * Max distance from the eye to the button centre. Entity interaction range is 3 blocks, measured
     * to the hitbox; staying well under it leaves room for not standing at the cell centre.
     */
    static final double REACH = 2.6;

    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST
    };
    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};

    enum Category {
        CLUTTER, WALL, CEILING, FLOOR
    }

    private record Cell(int x, int y, int z) {
    }

    private final Blueprint blueprint;
    private final Set<Cell> standable;

    SpotFinder(Blueprint blueprint, SpawnPoint entrance) {
        this.blueprint = blueprint;
        Vector offset = entrance.offset();
        Cell start = new Cell(offset.getBlockX(), offset.getBlockY(), offset.getBlockZ());
        if (!canStand(start.x, start.y, start.z)) {
            throw new IllegalStateException("entrance " + start + " is not a standable cell");
        }
        this.standable = walk(start);
    }

    private boolean canStand(int x, int y, int z) {
        return !blueprint.blocks(x, y, z) && !blueprint.blocks(x, y + 1, z) && blueprint.supports(x, y - 1, z);
    }

    /** Flood fill over standable cells: walk level, jump up one block, or drop down any height. */
    private Set<Cell> walk(Cell start) {
        Set<Cell> seen = new HashSet<>();
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Cell cell = queue.poll();
            for (BlockFace face : HORIZONTAL) {
                int nx = cell.x + face.getModX();
                int nz = cell.z + face.getModZ();
                int y = cell.y;
                Cell next = null;
                if (canStand(nx, y, nz)) {
                    next = new Cell(nx, y, nz);
                } else if (canStand(nx, y + 1, nz) && !blueprint.blocks(cell.x, y + 2, cell.z)) {
                    next = new Cell(nx, y + 1, nz);
                } else if (!blueprint.blocks(nx, y, nz) && !blueprint.blocks(nx, y + 1, nz)) {
                    int ny = y;
                    while (ny > 0 && !blueprint.blocks(nx, ny - 1, nz)) {
                        ny--;
                    }
                    if (ny < y && canStand(nx, ny, nz)) {
                        next = new Cell(nx, ny, nz);
                    }
                }
                if (next != null && seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }

    boolean isReachable(ButtonSpot spot) {
        int bx = spot.block().getBlockX();
        int by = spot.block().getBlockY();
        int bz = spot.block().getBlockZ();
        BlockFace face = spot.face();
        int ax = bx + face.getModX();
        int ay = by + face.getModY();
        int az = bz + face.getModZ();
        if (!blueprint.isFull(bx, by, bz) || !blueprint.isAir(ax, ay, az)) {
            return false;
        }
        // The button is a thin plate on the face, so aim at a point just off the attached block.
        Vector target = new Vector(ax + 0.5 - face.getModX() * 0.4, ay + 0.5 - face.getModY() * 0.4,
                az + 0.5 - face.getModZ() * 0.4);
        int r = (int) Math.ceil(REACH) + 1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r - 2; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Cell cell = new Cell(ax + dx, ay + dy, az + dz);
                    if (!standable.contains(cell)) {
                        continue;
                    }
                    Vector eye = new Vector(cell.x + 0.5, cell.y + EYE_HEIGHT, cell.z + 0.5);
                    if (eye.distance(target) <= REACH && clearLine(eye, target, ax, ay, az)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean clearLine(Vector from, Vector to, int ax, int ay, int az) {
        Vector step = to.clone().subtract(from);
        int samples = (int) Math.ceil(step.length() / 0.05);
        step.multiply(1.0 / samples);
        Vector point = from.clone();
        for (int i = 0; i <= samples; i++) {
            int x = point.getBlockX();
            int y = point.getBlockY();
            int z = point.getBlockZ();
            if (!(x == ax && y == ay && z == az) && blueprint.blocks(x, y, z)) {
                return false;
            }
            point.add(step);
        }
        return true;
    }

    Category categorize(ButtonSpot spot) {
        BlockVector b = spot.block();
        BlockFace face = spot.face();
        if (face == BlockFace.UP && b.getBlockY() == 0) {
            return Category.FLOOR;
        }
        if (face == BlockFace.DOWN && b.getBlockY() == blueprint.sizeY() - 1) {
            return Category.CEILING;
        }
        boolean outer = b.getBlockX() == 0 || b.getBlockZ() == 0
                || b.getBlockX() == blueprint.sizeX() - 1 || b.getBlockZ() == blueprint.sizeZ() - 1;
        if (outer && face.getModY() == 0) {
            return Category.WALL;
        }
        return Category.CLUTTER;
    }

    /** Every reachable spot, in a fixed order. */
    List<ButtonSpot> candidates() {
        List<ButtonSpot> result = new ArrayList<>();
        for (int y = 0; y < blueprint.sizeY(); y++) {
            for (int z = 0; z < blueprint.sizeZ(); z++) {
                for (int x = 0; x < blueprint.sizeX(); x++) {
                    if (!blueprint.isFull(x, y, z)) {
                        continue;
                    }
                    for (BlockFace face : FACES) {
                        ButtonSpot spot = new ButtonSpot(new BlockVector(x, y, z), face);
                        if (isReachable(spot)) {
                            result.add(spot);
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * Picks spots spread over the room, cycling through categories so walls, clutter, ceiling and
     * floor all get used, and keeping away from the entrance so the button is never right there.
     */
    List<ButtonSpot> pick(SpawnPoint entrance, int count, double minEntranceDistance, List<ButtonSpot> fixed) {
        Map<Category, List<ButtonSpot>> byCategory = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            byCategory.put(category, new ArrayList<>());
        }
        Vector entrancePoint = entrance.offset();
        for (ButtonSpot spot : candidates()) {
            if (center(spot).distance(entrancePoint) >= minEntranceDistance && !fixed.contains(spot)) {
                byCategory.get(categorize(spot)).add(spot);
            }
        }

        List<ButtonSpot> picked = new ArrayList<>(fixed);
        List<Vector> taken = new ArrayList<>();
        taken.add(entrancePoint);
        fixed.forEach(spot -> taken.add(center(spot)));

        Category[] order = {Category.CLUTTER, Category.WALL, Category.CEILING, Category.CLUTTER, Category.FLOOR,
                Category.WALL};
        int idle = 0;
        for (int turn = 0; picked.size() < count && idle < order.length; turn++) {
            List<ButtonSpot> pool = byCategory.get(order[turn % order.length]);
            ButtonSpot best = null;
            double bestDistance = -1;
            for (ButtonSpot spot : pool) {
                Vector c = center(spot);
                double nearest = Double.MAX_VALUE;
                for (Vector t : taken) {
                    nearest = Math.min(nearest, c.distanceSquared(t));
                }
                if (nearest > bestDistance) {
                    bestDistance = nearest;
                    best = spot;
                }
            }
            if (best == null) {
                idle++;
                continue;
            }
            idle = 0;
            pool.remove(best);
            picked.add(best);
            taken.add(center(best));
        }
        return List.copyOf(picked);
    }

    static Vector center(ButtonSpot spot) {
        BlockFace face = spot.face();
        return spot.block().clone().add(new Vector(0.5 + face.getModX(), 0.5 + face.getModY(), 0.5 + face.getModZ()));
    }
}
