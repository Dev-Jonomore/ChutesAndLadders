package mc.jonomore.chutes.game;

import java.util.UUID;
import mc.jonomore.chutes.world.ButtonSpot;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * A pawn's exit button in one room: a {@link BlockDisplay} of a button plus an {@link Interaction}
 * hitbox over it. Both are hidden by default and shown to the pawn's members only.
 */
final class ExitButton {

    private static final float HITBOX = 0.5f;
    private static final float FLAT_HITBOX_HEIGHT = 0.3f;

    private final BlockDisplay display;
    private final Interaction interaction;

    private ExitButton(BlockDisplay display, Interaction interaction) {
        this.display = display;
        this.interaction = interaction;
    }

    /**
     * @param roomOrigin min corner of the placed room template
     * @param spot       the support block and the face the button sits on
     */
    static ExitButton spawn(Location roomOrigin, ButtonSpot spot, boolean glowing) {
        World world = roomOrigin.getWorld();
        BlockFace face = spot.face();
        // The button occupies the block next to the support block, on the given face.
        int bx = roomOrigin.getBlockX() + spot.block().getBlockX() + face.getModX();
        int by = roomOrigin.getBlockY() + spot.block().getBlockY() + face.getModY();
        int bz = roomOrigin.getBlockZ() + spot.block().getBlockZ() + face.getModZ();

        Switch data = (Switch) Material.STONE_BUTTON.createBlockData();
        double hitX = bx + 0.5;
        double hitZ = bz + 0.5;
        double hitY;
        float hitHeight;
        switch (face) {
            case DOWN -> {
                data.setAttachedFace(FaceAttachable.AttachedFace.CEILING);
                data.setFacing(BlockFace.NORTH);
                hitY = by + 1 - FLAT_HITBOX_HEIGHT;
                hitHeight = FLAT_HITBOX_HEIGHT;
            }
            case NORTH, SOUTH, EAST, WEST -> {
                // Wall buttons face away from their support block, i.e. the same way as the face.
                data.setAttachedFace(FaceAttachable.AttachedFace.WALL);
                data.setFacing(face);
                // Pull the square hitbox back against the wall, where the button is drawn.
                hitX -= face.getModX() * (0.5 - HITBOX / 2);
                hitZ -= face.getModZ() * (0.5 - HITBOX / 2);
                hitY = by + 0.5 - HITBOX / 2;
                hitHeight = HITBOX;
            }
            default -> {
                // UP, and anything unexpected: a floor button.
                data.setAttachedFace(FaceAttachable.AttachedFace.FLOOR);
                data.setFacing(BlockFace.NORTH);
                hitY = by;
                hitHeight = FLAT_HITBOX_HEIGHT;
            }
        }

        // A block display renders from its own position as the block's min corner.
        BlockDisplay display = world.spawn(new Location(world, bx, by, bz), BlockDisplay.class, entity -> {
            hide(entity);
            entity.setBlock(data);
            if (glowing) {
                entity.setGlowing(true);
                entity.setGlowColorOverride(Color.YELLOW);
            }
        });
        Interaction interaction = world.spawn(new Location(world, hitX, hitY, hitZ), Interaction.class, entity -> {
            hide(entity);
            entity.setInteractionWidth(HITBOX);
            entity.setInteractionHeight(hitHeight);
            entity.setResponsive(true);
        });
        return new ExitButton(display, interaction);
    }

    private static void hide(Entity entity) {
        entity.setVisibleByDefault(false);
        entity.setPersistent(false);
        entity.setInvulnerable(true);
        entity.setSilent(true);
    }

    UUID interactionId() {
        return interaction.getUniqueId();
    }

    void showTo(Plugin plugin, Player player) {
        player.showEntity(plugin, display);
        player.showEntity(plugin, interaction);
    }

    void hideFrom(Plugin plugin, Player player) {
        player.hideEntity(plugin, display);
        player.hideEntity(plugin, interaction);
    }

    void remove() {
        display.remove();
        interaction.remove();
    }
}
