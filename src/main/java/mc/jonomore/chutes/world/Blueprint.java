package mc.jonomore.chutes.world;

import org.bukkit.util.BlockVector;

/**
 * A box of block-data strings built in code, used for the built-in templates. Cells are addressed
 * from the min corner; {@code null} is air. Each block also carries a {@link Kind} so the geometry
 * can be analysed (where a player can stand, what a button can attach to) without a running server.
 */
final class Blueprint {

    enum Kind {
        /** Full cube: a player can stand on it and a button can attach to it. */
        FULL,
        /** Something a player can stand on but a button should not sit on, such as stairs. */
        SUPPORT,
        /** Blocks movement and line of sight, nothing more: lanterns, fences, plants. */
        DECO
    }

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final String[] blocks;
    private final Kind[] kinds;

    Blueprint(int sizeX, int sizeY, int sizeZ) {
        if (sizeX < 3 || sizeY < 3 || sizeZ < 3) {
            throw new IllegalArgumentException("blueprint too small");
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.blocks = new String[sizeX * sizeY * sizeZ];
        this.kinds = new Kind[blocks.length];
    }

    int sizeX() {
        return sizeX;
    }

    int sizeY() {
        return sizeY;
    }

    int sizeZ() {
        return sizeZ;
    }

    BlockVector size() {
        return new BlockVector(sizeX, sizeY, sizeZ);
    }

    boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
    }

    private int index(int x, int y, int z) {
        if (!inside(x, y, z)) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ", " + z + ") outside blueprint");
        }
        return (y * sizeZ + z) * sizeX + x;
    }

    /** Block data string, or null for air. */
    String block(int x, int y, int z) {
        return blocks[index(x, y, z)];
    }

    Kind kind(int x, int y, int z) {
        return kinds[index(x, y, z)];
    }

    boolean isAir(int x, int y, int z) {
        return inside(x, y, z) && blocks[index(x, y, z)] == null;
    }

    boolean isFull(int x, int y, int z) {
        return inside(x, y, z) && kinds[index(x, y, z)] == Kind.FULL;
    }

    /** Can a player stand on top of this cell. */
    boolean supports(int x, int y, int z) {
        if (!inside(x, y, z)) {
            return false;
        }
        Kind kind = kinds[index(x, y, z)];
        return kind == Kind.FULL || kind == Kind.SUPPORT;
    }

    /** Anything that is not air, including everything outside the box. */
    boolean blocks(int x, int y, int z) {
        return !inside(x, y, z) || blocks[index(x, y, z)] != null;
    }

    Blueprint set(int x, int y, int z, String block, Kind kind) {
        int i = index(x, y, z);
        blocks[i] = block;
        kinds[i] = block == null ? null : kind;
        return this;
    }

    Blueprint full(int x, int y, int z, String block) {
        return set(x, y, z, block, Kind.FULL);
    }

    Blueprint deco(int x, int y, int z, String block) {
        return set(x, y, z, block, Kind.DECO);
    }

    Blueprint air(int x, int y, int z) {
        return set(x, y, z, null, null);
    }

    /** Fill the inclusive box between two corners. */
    Blueprint fill(int x1, int y1, int z1, int x2, int y2, int z2, String block, Kind kind) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    set(x, y, z, block, kind);
                }
            }
        }
        return this;
    }

    Blueprint fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        return fill(x1, y1, z1, x2, y2, z2, block, Kind.FULL);
    }

    /** Floor at y = 0, ceiling at the top layer, walls on the four sides. */
    Blueprint shell(String floor, String wall, String ceiling) {
        fill(0, 0, 0, sizeX - 1, 0, sizeZ - 1, floor);
        fill(0, sizeY - 1, 0, sizeX - 1, sizeY - 1, sizeZ - 1, ceiling);
        fill(0, 1, 0, sizeX - 1, sizeY - 2, 0, wall);
        fill(0, 1, sizeZ - 1, sizeX - 1, sizeY - 2, sizeZ - 1, wall);
        fill(0, 1, 0, 0, sizeY - 2, sizeZ - 1, wall);
        fill(sizeX - 1, 1, 0, sizeX - 1, sizeY - 2, sizeZ - 1, wall);
        return this;
    }

    /** Replace ceiling blocks with a light block on a grid, offset so lamps sit inside the walls. */
    Blueprint ceilingLights(String light, int step) {
        int top = sizeY - 1;
        for (int x = 2; x < sizeX - 1; x += step) {
            for (int z = 2; z < sizeZ - 1; z += step) {
                full(x, top, z, light);
            }
        }
        return this;
    }

    /** A horizontal band of a different wall block, e.g. a wainscot or window strip. */
    Blueprint wallBand(int y1, int y2, String block) {
        for (int y = y1; y <= y2; y++) {
            for (int x = 0; x < sizeX; x++) {
                full(x, y, 0, block);
                full(x, y, sizeZ - 1, block);
            }
            for (int z = 0; z < sizeZ; z++) {
                full(0, y, z, block);
                full(sizeX - 1, y, z, block);
            }
        }
        return this;
    }
}
