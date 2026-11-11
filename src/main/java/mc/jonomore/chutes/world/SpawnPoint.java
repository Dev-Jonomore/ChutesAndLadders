package mc.jonomore.chutes.world;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/** A position relative to a template min corner, with facing. */
public record SpawnPoint(Vector offset, float yaw, float pitch) {

    public Location at(Location origin) {
        Location location = origin.clone().add(offset);
        location.setYaw(yaw);
        location.setPitch(pitch);
        return location;
    }
}
