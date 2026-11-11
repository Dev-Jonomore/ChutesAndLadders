package mc.jonomore.chutes.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class BuiltinTemplatesTest {

    private static Blueprint blueprint(RoomTemplate template) {
        return ((GeneratedTemplate) template).blueprint();
    }

    @Test
    void roomsHaveManyReachableDistinctSpots() {
        List<RoomTemplate> rooms = BuiltinTemplates.of(TemplateType.ROOM);
        assertTrue(rooms.size() >= 4, "at least four room variants");
        for (RoomTemplate room : rooms) {
            Blueprint bp = blueprint(room);
            SpotFinder finder = new SpotFinder(bp, room.entrance());
            List<ButtonSpot> spots = room.buttonSpots();
            assertTrue(spots.size() >= 8, room.id() + " has only " + spots.size() + " spots");
            assertEquals(spots.size(), new HashSet<>(spots).size(), room.id() + " has duplicate spots");
            Set<SpotFinder.Category> categories = EnumSet.noneOf(SpotFinder.Category.class);
            for (ButtonSpot spot : spots) {
                assertTrue(finder.isReachable(spot), room.id() + " spot not reachable: " + spot);
                categories.add(finder.categorize(spot));
            }
            assertTrue(categories.size() >= 3, room.id() + " spots only in " + categories);
        }
    }

    @Test
    void roomsAreDistinctAndFitTheFootprint() {
        List<RoomTemplate> rooms = BuiltinTemplates.of(TemplateType.ROOM);
        assertEquals(rooms.size(), rooms.stream().map(RoomTemplate::size).distinct().count());
        assertTrue(rooms.stream().anyMatch(r -> r.size().getBlockX() <= 16 && r.size().getBlockZ() <= 16),
                "a room must fit the minimum room spacing");
        for (RoomTemplate template : rooms) {
            assertTrue(template.size().getBlockX() <= 64 && template.size().getBlockZ() <= 64);
            assertTrue(TemplateValidation.problems(template.type(), template.size(), 64, template.entrance(),
                    template.buttonSpots(), template.mobSpawns()).isEmpty());
        }
    }

    @Test
    void breakRoomStartsWithPedestalFacingEntrance() {
        RoomTemplate room = BuiltinTemplates.of(TemplateType.BREAK).getFirst();
        ButtonSpot first = room.buttonSpots().getFirst();
        assertEquals(new ButtonSpot(new BlockVector(7, 2, 7), BlockFace.NORTH), first);
        assertTrue(new SpotFinder(blueprint(room), room.entrance()).isReachable(first));
        assertTrue(room.size().getBlockX() <= 16 && room.size().getBlockZ() <= 16);
    }

    @Test
    void arenaIsEnclosedWithStandableSpawns() {
        RoomTemplate arena = BuiltinTemplates.of(TemplateType.ARENA).getFirst();
        Blueprint bp = blueprint(arena);
        assertTrue(arena.mobSpawns().size() >= 6);
        for (Vector spawn : arena.mobSpawns()) {
            int x = spawn.getBlockX();
            int y = spawn.getBlockY();
            int z = spawn.getBlockZ();
            assertTrue(bp.isAir(x, y, z) && bp.isAir(x, y + 1, z) && bp.isFull(x, y - 1, z), "spawn " + spawn);
        }
        for (int x = 0; x < bp.sizeX(); x++) {
            for (int z = 0; z < bp.sizeZ(); z++) {
                assertTrue(bp.isFull(x, bp.sizeY() - 1, z), "roof hole");
                assertTrue(bp.isFull(x, 0, z), "floor hole");
            }
        }
        for (int y = 0; y < bp.sizeY(); y++) {
            for (int i = 0; i < bp.sizeX(); i++) {
                assertFalse(bp.isAir(i, y, 0) || bp.isAir(i, y, bp.sizeZ() - 1) || bp.isAir(0, y, i)
                        || bp.isAir(bp.sizeX() - 1, y, i), "wall hole");
            }
        }
        assertTrue(arena.size().getBlockX() <= 128 && arena.size().getBlockZ() <= 128);
    }
}
