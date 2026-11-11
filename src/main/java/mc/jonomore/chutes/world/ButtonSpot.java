package mc.jonomore.chutes.world;

import org.bukkit.block.BlockFace;
import org.bukkit.util.BlockVector;

/**
 * A place an exit button can appear.
 *
 * @param block offset, from the template min corner, of the solid block the button is attached to
 * @param face  the face of that block the button sits on; the button occupies the adjacent block
 */
public record ButtonSpot(BlockVector block, BlockFace face) {
}
