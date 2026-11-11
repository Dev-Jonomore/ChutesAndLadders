package mc.jonomore.chutes.world;

import org.bukkit.Location;
import org.bukkit.World;

/** The void world boards live in, and the region layout inside it. Regions never overlap. */
public interface BoardWorld {

    World world();

    /** Min corner of the footprint (layout.room-spacing squared) for one room of one session. */
    Location roomOrigin(int sessionSlot, int roomIndex);

    /** Min corner of the 128 x 128 arena region for the transit challenge of one pawn. */
    Location arenaOrigin(int sessionSlot, int pawnSlot);
}
