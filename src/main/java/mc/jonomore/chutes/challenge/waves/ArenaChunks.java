package mc.jonomore.chutes.challenge.waves;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

/**
 * Keeps the chunks under an arena loaded with plugin chunk tickets. A plugin holds at most
 * one ticket per chunk, so tickets are reference counted across all wave challenges: two
 * arenas touching the same chunk must not release each other's ticket.
 */
final class ArenaChunks {

    private record ChunkKey(UUID world, int x, int z) {
    }

    private static final Map<ChunkKey, Integer> CLAIMS = new HashMap<>();

    private final Plugin plugin;
    private final World world;
    private final List<ChunkKey> keys;
    private boolean released;

    private ArenaChunks(Plugin plugin, World world, List<ChunkKey> keys) {
        this.plugin = plugin;
        this.world = world;
        this.keys = keys;
    }

    /** Loads and tickets every chunk the box touches. Main thread. */
    static ArenaChunks claim(Plugin plugin, World world, BoundingBox box) {
        int minX = (int) Math.floor(box.getMinX()) >> 4;
        int minZ = (int) Math.floor(box.getMinZ()) >> 4;
        int maxX = ((int) Math.ceil(box.getMaxX()) - 1) >> 4;
        int maxZ = ((int) Math.ceil(box.getMaxZ()) - 1) >> 4;
        List<ChunkKey> keys = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ChunkKey key = new ChunkKey(world.getUID(), x, z);
                keys.add(key);
                if (CLAIMS.merge(key, 1, Integer::sum) == 1) {
                    world.addPluginChunkTicket(x, z, plugin);
                }
                world.getChunkAt(x, z);
            }
        }
        return new ArenaChunks(plugin, world, keys);
    }

    /** Idempotent. */
    void release() {
        if (released) {
            return;
        }
        released = true;
        for (ChunkKey key : keys) {
            Integer left = CLAIMS.computeIfPresent(key, (k, count) -> count > 1 ? count - 1 : null);
            if (left == null) {
                world.removePluginChunkTicket(key.x(), key.z(), plugin);
            }
        }
    }
}
