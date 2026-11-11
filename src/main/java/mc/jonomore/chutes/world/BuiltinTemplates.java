package mc.jonomore.chutes.world;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import mc.jonomore.chutes.world.Blueprint.Kind;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;

/**
 * Fallback templates generated in code, so the game is playable with nothing authored. The
 * geometry is fixed; button spots are picked by {@link SpotFinder} so each one is checked to be
 * walkable-to, in reach and in sight from the entrance's reachable area.
 */
final class BuiltinTemplates {

    static final String PREFIX = "builtin_";
    static final int ROOM_SPOTS = 12;
    static final double MIN_ENTRANCE_DISTANCE = 4.0;

    private static final String LANTERN = "minecraft:lantern[hanging=true]";

    private static volatile Map<TemplateType, List<RoomTemplate>> cache;

    private BuiltinTemplates() {
    }

    /** All built-ins of a type, sorted by id, regardless of footprint. */
    static List<RoomTemplate> of(TemplateType type) {
        Map<TemplateType, List<RoomTemplate>> built = cache;
        if (built == null) {
            built = build();
            cache = built;
        }
        return built.get(type);
    }

    private static Map<TemplateType, List<RoomTemplate>> build() {
        Map<TemplateType, List<RoomTemplate>> map = new EnumMap<>(TemplateType.class);
        map.put(TemplateType.ROOM, sorted(List.of(crates(), cellar(), library(), vault())));
        map.put(TemplateType.BREAK, List.of(breakRoom()));
        map.put(TemplateType.ARENA, List.of(arena()));
        return map;
    }

    private static List<RoomTemplate> sorted(List<RoomTemplate> templates) {
        return templates.stream().sorted(Comparator.comparing(RoomTemplate::id)).toList();
    }

    /** Entrance one block in from the low-z wall, centred, looking into the room (+z). */
    private static SpawnPoint entrance(Blueprint bp) {
        return new SpawnPoint(new Vector(bp.sizeX() / 2 + 0.5, 1, 1.5), 0f, 0f);
    }

    private static RoomTemplate room(String id, Blueprint bp) {
        SpawnPoint entrance = entrance(bp);
        List<ButtonSpot> spots = new SpotFinder(bp, entrance).pick(entrance, ROOM_SPOTS, MIN_ENTRANCE_DISTANCE, List.of());
        return new GeneratedTemplate(PREFIX + id, TemplateType.ROOM, bp, entrance, spots, List.of());
    }

    /** Small storeroom: crate stacks, a hay pile and a high shelf to look under. */
    static RoomTemplate crates() {
        Blueprint bp = new Blueprint(13, 6, 13)
                .shell("minecraft:spruce_planks", "minecraft:oak_planks", "minecraft:spruce_planks")
                .ceilingLights("minecraft:glowstone", 4);
        for (int x : new int[] {0, 12}) {
            for (int z : new int[] {0, 12}) {
                bp.fill(x, 1, z, x, 4, z, "minecraft:oak_log");
            }
        }
        bp.fill(2, 1, 4, 3, 2, 5, "minecraft:hay_block");
        bp.fill(8, 1, 3, 9, 1, 4, "minecraft:stripped_oak_wood");
        bp.full(9, 2, 4, "minecraft:stripped_oak_wood");
        bp.fill(9, 1, 8, 10, 3, 8, "minecraft:brown_wool");
        bp.fill(4, 1, 8, 6, 3, 8, "minecraft:oak_planks");
        bp.fill(1, 3, 9, 3, 3, 11, "minecraft:spruce_planks");
        bp.deco(6, 4, 5, LANTERN);
        bp.deco(7, 1, 11, "minecraft:oak_fence");
        bp.deco(7, 2, 11, "minecraft:lantern");
        return room("crates", bp);
    }

    /** Stone cellar: two pillars, an arch to walk under and a raised corner reached by a step. */
    static RoomTemplate cellar() {
        Blueprint bp = new Blueprint(15, 7, 15)
                .shell("minecraft:polished_andesite", "minecraft:stone_bricks", "minecraft:stone_bricks")
                .wallBand(1, 1, "minecraft:mossy_stone_bricks")
                .ceilingLights("minecraft:sea_lantern", 4);
        bp.fill(4, 1, 5, 5, 5, 6, "minecraft:stone_bricks");
        bp.fill(4, 3, 5, 5, 3, 6, "minecraft:chiseled_stone_bricks");
        bp.fill(9, 1, 9, 10, 5, 10, "minecraft:stone_bricks");
        bp.fill(9, 3, 9, 10, 3, 10, "minecraft:chiseled_stone_bricks");
        bp.fill(1, 3, 5, 3, 3, 6, "minecraft:stone_bricks");
        bp.fill(10, 1, 1, 13, 2, 4, "minecraft:polished_andesite");
        bp.set(9, 1, 3, "minecraft:stone_brick_stairs[facing=east]", Kind.SUPPORT);
        bp.fill(5, 1, 12, 8, 2, 12, "minecraft:mossy_cobblestone");
        bp.full(1, 1, 12, "minecraft:cracked_stone_bricks");
        bp.deco(7, 5, 8, LANTERN);
        bp.deco(12, 5, 12, LANTERN);
        return room("cellar", bp);
    }

    /** Tall library: bookshelf walls, aisles of shelves and a balcony along the back reached by stairs. */
    static RoomTemplate library() {
        Blueprint bp = new Blueprint(21, 9, 17)
                .shell("minecraft:dark_oak_planks", "minecraft:spruce_planks", "minecraft:spruce_planks")
                .wallBand(1, 3, "minecraft:bookshelf")
                .ceilingLights("minecraft:glowstone", 3);
        for (int x : new int[] {5, 10, 15}) {
            bp.fill(x, 1, 3, x, 3, 8, "minecraft:bookshelf");
            bp.deco(x, 4, 5, "minecraft:lantern");
        }
        bp.fill(1, 4, 12, 19, 4, 15, "minecraft:spruce_planks");
        // Stairs up the east side to the balcony, ascending towards +z.
        for (int step = 0; step < 4; step++) {
            int z = 8 + step;
            if (step > 0) {
                bp.fill(19, 1, z, 19, step, z, "minecraft:spruce_planks");
            }
            bp.set(19, step + 1, z, "minecraft:spruce_stairs[facing=south]", Kind.SUPPORT);
        }
        bp.fill(2, 1, 9, 3, 1, 10, "minecraft:dark_oak_log");
        bp.fill(12, 1, 10, 13, 1, 10, "minecraft:dark_oak_log");
        bp.deco(7, 3, 12, LANTERN);
        bp.deco(13, 3, 12, LANTERN);
        return room("library", bp);
    }

    /** Large blackstone vault: a stepped dais, a ring of pillars, L-shaped walls and a catwalk. */
    static RoomTemplate vault() {
        Blueprint bp = new Blueprint(25, 10, 25)
                .shell("minecraft:deepslate_tiles", "minecraft:polished_blackstone_bricks", "minecraft:polished_deepslate")
                .wallBand(4, 4, "minecraft:chiseled_polished_blackstone")
                .ceilingLights("minecraft:shroomlight", 4);
        bp.fill(9, 1, 9, 15, 1, 15, "minecraft:polished_deepslate");
        bp.fill(11, 2, 11, 13, 2, 13, "minecraft:polished_deepslate");
        bp.full(12, 3, 12, "minecraft:crying_obsidian");
        int[][] pillars = {{5, 5}, {12, 5}, {19, 5}, {5, 12}, {19, 12}, {5, 19}, {12, 19}, {19, 19}};
        for (int[] p : pillars) {
            bp.fill(p[0], 1, p[1], p[0], 8, p[1], "minecraft:deepslate_bricks");
        }
        bp.fill(17, 1, 7, 21, 3, 7, "minecraft:polished_blackstone_bricks");
        bp.fill(21, 1, 7, 21, 3, 10, "minecraft:polished_blackstone_bricks");
        bp.fill(16, 1, 18, 16, 3, 22, "minecraft:polished_blackstone_bricks");
        bp.fill(16, 1, 18, 20, 3, 18, "minecraft:polished_blackstone_bricks");
        // Catwalk along the west wall, reached by steps at x = 4.
        bp.fill(1, 4, 1, 3, 4, 23, "minecraft:polished_deepslate");
        for (int step = 0; step < 3; step++) {
            int z = 17 + step;
            if (step > 0) {
                bp.fill(4, 1, z, 4, step, z, "minecraft:deepslate_bricks");
            }
            bp.set(4, step + 1, z, "minecraft:deepslate_brick_stairs[facing=south]", Kind.SUPPORT);
        }
        bp.deco(12, 8, 8, "minecraft:soul_lantern[hanging=true]");
        bp.deco(8, 8, 15, "minecraft:soul_lantern[hanging=true]");
        bp.deco(17, 8, 13, "minecraft:soul_lantern[hanging=true]");
        return room("vault", bp);
    }

    /** Calm, bright break room. The first spot is on a pedestal facing the entrance. */
    static RoomTemplate breakRoom() {
        Blueprint bp = new Blueprint(15, 7, 15)
                .shell("minecraft:smooth_quartz", "minecraft:calcite", "minecraft:smooth_quartz")
                .ceilingLights("minecraft:sea_lantern", 3);
        bp.full(7, 1, 7, "minecraft:quartz_pillar");
        bp.full(7, 2, 7, "minecraft:chiseled_quartz_block");
        for (int x : new int[] {1, 13}) {
            for (int z : new int[] {1, 13}) {
                bp.full(x, 1, z, "minecraft:moss_block");
                bp.deco(x, 2, z, "minecraft:flowering_azalea");
            }
        }
        bp.set(3, 1, 7, "minecraft:oak_stairs[facing=west]", Kind.SUPPORT);
        bp.set(11, 1, 7, "minecraft:oak_stairs[facing=east]", Kind.SUPPORT);
        SpawnPoint entrance = entrance(bp);
        ButtonSpot pedestal = new ButtonSpot(new BlockVector(7, 2, 7), BlockFace.NORTH);
        List<ButtonSpot> spots = new SpotFinder(bp, entrance).pick(entrance, 5, MIN_ENTRANCE_DISTANCE, List.of(pedestal));
        return new GeneratedTemplate(PREFIX + "break", TemplateType.BREAK, bp, entrance, spots, List.of());
    }

    /** Roofed 39 x 39 arena with four cover pillars. The roof keeps undead from burning at noon. */
    static RoomTemplate arena() {
        Blueprint bp = new Blueprint(41, 9, 41)
                .shell("minecraft:smooth_stone", "minecraft:stone_bricks", "minecraft:deepslate_tiles")
                .wallBand(1, 1, "minecraft:mossy_stone_bricks")
                .ceilingLights("minecraft:glowstone", 4);
        for (int x : new int[] {9, 29}) {
            for (int z : new int[] {9, 29}) {
                bp.fill(x, 1, z, x + 2, 7, z + 2, "minecraft:polished_andesite");
            }
        }
        SpawnPoint entrance = entrance(bp);
        List<Vector> spawns = List.of(
                new Vector(8.5, 1, 20.5), new Vector(32.5, 1, 20.5), new Vector(20.5, 1, 20.5),
                new Vector(20.5, 1, 36.5), new Vector(8.5, 1, 35.5), new Vector(32.5, 1, 35.5),
                new Vector(14.5, 1, 26.5), new Vector(26.5, 1, 26.5));
        // Arenas have no exit button; one spot satisfies the RoomTemplate contract.
        List<ButtonSpot> spots = List.of(new ButtonSpot(new BlockVector(0, 2, 20), BlockFace.EAST));
        return new GeneratedTemplate(PREFIX + "arena", TemplateType.ARENA, bp, entrance, spots, spawns);
    }
}
