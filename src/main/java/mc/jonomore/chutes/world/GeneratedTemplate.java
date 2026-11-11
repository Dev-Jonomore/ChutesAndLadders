package mc.jonomore.chutes.world;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;

/** A built-in template whose blocks come from a {@link Blueprint} instead of a structure file. */
final class GeneratedTemplate implements RoomTemplate {

    private final String id;
    private final TemplateType type;
    private final Blueprint blueprint;
    private final SpawnPoint entrance;
    private final List<ButtonSpot> buttonSpots;
    private final List<Vector> mobSpawns;
    private final Map<String, BlockData> dataCache = new HashMap<>();

    GeneratedTemplate(String id, TemplateType type, Blueprint blueprint, SpawnPoint entrance,
            List<ButtonSpot> buttonSpots, List<Vector> mobSpawns) {
        this.id = id;
        this.type = type;
        this.blueprint = blueprint;
        this.entrance = entrance;
        this.buttonSpots = List.copyOf(buttonSpots);
        this.mobSpawns = List.copyOf(mobSpawns);
    }

    Blueprint blueprint() {
        return blueprint;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public TemplateType type() {
        return type;
    }

    @Override
    public BlockVector size() {
        return blueprint.size();
    }

    @Override
    public SpawnPoint entrance() {
        return entrance;
    }

    @Override
    public List<ButtonSpot> buttonSpots() {
        return buttonSpots;
    }

    @Override
    public List<Vector> mobSpawns() {
        return mobSpawns;
    }

    @Override
    public void place(Location origin) {
        Location base = Regions.blockOrigin(origin);
        Regions.loadChunks(base, size());
        World world = base.getWorld();
        int ox = base.getBlockX();
        int oy = base.getBlockY();
        int oz = base.getBlockZ();
        for (int y = 0; y < blueprint.sizeY(); y++) {
            for (int z = 0; z < blueprint.sizeZ(); z++) {
                for (int x = 0; x < blueprint.sizeX(); x++) {
                    Block block = world.getBlockAt(ox + x, oy + y, oz + z);
                    String data = blueprint.block(x, y, z);
                    if (data == null) {
                        if (!block.getType().isAir()) {
                            block.setType(Material.AIR, false);
                        }
                    } else {
                        block.setBlockData(dataCache.computeIfAbsent(data, Bukkit::createBlockData), false);
                    }
                }
            }
        }
    }

    @Override
    public void clear(Location origin) {
        Regions.clear(Regions.blockOrigin(origin), size());
    }

    @Override
    public String toString() {
        return "GeneratedTemplate[" + type + "/" + id + "]";
    }
}
