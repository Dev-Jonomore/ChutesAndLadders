package mc.jonomore.chutes.world;

import java.util.List;
import java.util.Random;
import org.bukkit.Location;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;

/** A template authored in-game: a structure file plus the markers from its sidecar. */
final class SavedTemplate implements RoomTemplate {

    private final String id;
    private final TemplateType type;
    private final Structure structure;
    private final SpawnPoint entrance;
    private final List<ButtonSpot> buttonSpots;
    private final List<Vector> mobSpawns;

    SavedTemplate(String id, TemplateType type, Structure structure, SpawnPoint entrance,
            List<ButtonSpot> buttonSpots, List<Vector> mobSpawns) {
        this.id = id;
        this.type = type;
        this.structure = structure;
        this.entrance = entrance;
        this.buttonSpots = List.copyOf(buttonSpots);
        this.mobSpawns = List.copyOf(mobSpawns);
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
        return structure.getSize().clone();
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
        // Integrity 1 places every block, so the random source is never consulted.
        structure.place(base, true, StructureRotation.NONE, Mirror.NONE, 0, 1f, new Random(0));
    }

    @Override
    public void clear(Location origin) {
        Regions.clear(Regions.blockOrigin(origin), size());
    }

    @Override
    public String toString() {
        return "SavedTemplate[" + type + "/" + id + "]";
    }
}
