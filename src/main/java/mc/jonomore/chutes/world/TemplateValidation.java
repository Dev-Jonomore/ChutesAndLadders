package mc.jonomore.chutes.world;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;

/** Rules every template must satisfy, with messages meant for the template author. */
final class TemplateValidation {

    private TemplateValidation() {
    }

    /** Empty when the template is valid. Offsets are relative to the min corner. */
    static List<String> problems(TemplateType type, BlockVector size, int footprint, SpawnPoint entrance,
            List<ButtonSpot> buttons, List<Vector> spawns) {
        List<String> problems = new ArrayList<>();
        if (size.getBlockX() < 1 || size.getBlockY() < 1 || size.getBlockZ() < 1) {
            problems.add("size " + format(size) + " is empty");
        }
        if (size.getBlockX() > footprint || size.getBlockZ() > footprint) {
            problems.add("size " + size.getBlockX() + " x " + size.getBlockZ() + " (x by z) is larger than the "
                    + footprint + " x " + footprint + " footprint allowed for " + type + " templates");
        }
        if (entrance == null) {
            problems.add("no entrance set");
        } else if (!inside(size, entrance.offset())) {
            problems.add("entrance " + format(entrance.offset()) + " is outside the selection");
        }
        if (type != TemplateType.ARENA && buttons.isEmpty()) {
            problems.add("no button spots set");
        }
        if (type == TemplateType.ARENA && spawns.isEmpty()) {
            problems.add("no mob spawns set");
        }
        for (ButtonSpot spot : buttons) {
            BlockVector b = spot.block();
            Vector front = b.clone().add(spot.face().getDirection());
            if (!insideBlock(size, b) || !insideBlock(size, front)) {
                problems.add("button spot " + format(b) + " " + spot.face() + " is not fully inside the selection");
            }
        }
        for (Vector spawn : spawns) {
            if (!inside(size, spawn)) {
                problems.add("mob spawn " + format(spawn) + " is outside the selection");
            }
        }
        return problems;
    }

    private static boolean inside(BlockVector size, Vector offset) {
        return offset.getX() >= 0 && offset.getY() >= 0 && offset.getZ() >= 0
                && offset.getX() < size.getBlockX() && offset.getY() < size.getBlockY()
                && offset.getZ() < size.getBlockZ();
    }

    private static boolean insideBlock(BlockVector size, Vector block) {
        return inside(size, new Vector(block.getBlockX(), block.getBlockY(), block.getBlockZ()));
    }

    static String format(Vector v) {
        if (v instanceof BlockVector) {
            return v.getBlockX() + " " + v.getBlockY() + " " + v.getBlockZ();
        }
        return String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", v.getX(), v.getY(), v.getZ());
    }
}
