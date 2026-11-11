package mc.jonomore.chutes.world;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;

/**
 * A room or arena that can be placed in the world. All offsets are relative to the min corner.
 */
public interface RoomTemplate {

    String id();

    TemplateType type();

    /** Extent in blocks; never larger than the region it is placed in. */
    BlockVector size();

    SpawnPoint entrance();

    /** At least one. */
    List<ButtonSpot> buttonSpots();

    /** Mob spawn offsets; only meaningful for ARENA templates, at least one there. */
    List<Vector> mobSpawns();

    /** Place the blocks with the min corner at origin. Main thread. */
    void place(Location origin);

    /** Remove everything {@link #place(Location)} put there, including entities inside. */
    void clear(Location origin);
}
