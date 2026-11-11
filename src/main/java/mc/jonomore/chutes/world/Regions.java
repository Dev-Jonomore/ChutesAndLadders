package mc.jonomore.chutes.world;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.BlockVector;

/** Block-box helpers shared by the template implementations. Main thread only. */
final class Regions {

    private Regions() {
    }

    /** Loads (generating if needed) every chunk the box touches, so block edits never hit an unloaded chunk. */
    static void loadChunks(Location origin, BlockVector size) {
        World world = origin.getWorld();
        int minX = origin.getBlockX();
        int minZ = origin.getBlockZ();
        int maxX = minX + size.getBlockX() - 1;
        int maxZ = minZ + size.getBlockZ() - 1;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                world.getChunkAt(cx, cz);
            }
        }
    }

    /** Block-aligned copy of a location, so offsets added to it land on block corners. */
    static Location blockOrigin(Location origin) {
        return new Location(origin.getWorld(), origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
    }

    /** Sets the whole box to air and removes every non-player entity inside it. */
    static void clear(Location origin, BlockVector size) {
        World world = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        loadChunks(origin, size);

        // Entities first, so hanging decorations are gone before their support blocks.
        BoundingBox box = new BoundingBox(ox, oy, oz,
                ox + size.getBlockX(), oy + size.getBlockY(), oz + size.getBlockZ());
        for (Entity entity : world.getNearbyEntities(box)) {
            if (!(entity instanceof Player)) {
                entity.remove();
            }
        }

        for (int y = oy + size.getBlockY() - 1; y >= oy; y--) {
            for (int x = ox; x < ox + size.getBlockX(); x++) {
                for (int z = oz; z < oz + size.getBlockZ(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.getType().isAir()) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }
}
