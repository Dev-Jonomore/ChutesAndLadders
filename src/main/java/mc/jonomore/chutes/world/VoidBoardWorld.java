package mc.jonomore.chutes.world;

import java.util.List;
import java.util.Random;
import mc.jonomore.chutes.ChutesConfig;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.Plugin;

/**
 * The dedicated void world and its region layout.
 *
 * <p>Session slot {@code s} owns x in {@code [s * sessionSpacing, (s + 1) * sessionSpacing)}. Its rooms
 * form a column along +z at the low-x edge of that strip, {@code roomSpacing} wide. Its arenas form a
 * second column further along x, 128 wide, one every 160 blocks of z. Both columns run from base-y up,
 * so nothing overlaps.
 */
public final class VoidBoardWorld implements BoardWorld {

    public static final int ARENA_SIZE = 128;
    /** Arena pitch along z: 128 blocks of arena plus a 32 block gap. */
    public static final int ARENA_PITCH_Z = 160;
    /** Minimum gap along x between the room column and the arena column. */
    private static final int COLUMN_GAP = 64;
    /** Fixed noon. */
    private static final long NOON = 6000L;

    private final World world;
    private final int roomSpacing;
    private final int sessionSpacing;
    private final int baseY;
    private final int arenaOffsetX;

    public VoidBoardWorld(Plugin plugin, ChutesConfig config) {
        ChutesConfig.Layout layout = config.layout();
        this.roomSpacing = layout.roomSpacing();
        this.sessionSpacing = layout.sessionSpacing();
        this.baseY = layout.baseY();
        // Chunk aligned, so arena chunks are never shared with room chunks.
        this.arenaOffsetX = Math.ceilDiv(roomSpacing + COLUMN_GAP, 16) * 16;
        if (arenaOffsetX + ARENA_SIZE > sessionSpacing) {
            throw new IllegalArgumentException("layout.session-spacing is " + sessionSpacing
                    + " but must be at least " + (arenaOffsetX + ARENA_SIZE) + " to fit the " + roomSpacing
                    + " wide room column, a " + COLUMN_GAP + " block gap and the " + ARENA_SIZE
                    + " wide arena column (raise session-spacing or lower room-spacing)");
        }

        this.world = loadOrCreate(plugin, config.world());
        if (baseY < world.getMinHeight() || baseY + 16 > world.getMaxHeight()) {
            throw new IllegalArgumentException("layout.base-y is " + baseY + " but must be between "
                    + world.getMinHeight() + " and " + (world.getMaxHeight() - 16) + " in world " + world.getName());
        }
        configure(plugin, world);
    }

    private World loadOrCreate(Plugin plugin, String name) {
        NamespacedKey key;
        try {
            key = NamespacedKey.minecraft(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("world name '" + name
                    + "' is not valid; use lowercase letters, digits, '_', '-' and '.'");
        }
        World existing = Bukkit.getWorld(key);
        if (existing != null) {
            if (!(existing.getGenerator() instanceof VoidGenerator)) {
                plugin.getSLF4JLogger().warn("World {} was already loaded without the void generator; "
                        + "new chunks may not be empty", name);
            }
            return existing;
        }
        World created = WorldCreator.ofKey(key)
                .environment(World.Environment.NORMAL)
                .generator(new VoidGenerator(baseY))
                .biomeProvider(new VoidBiomes())
                .generateStructures(false)
                .createWorld();
        if (created == null) {
            throw new IllegalStateException("could not load or create world " + name);
        }
        return created;
    }

    private static void configure(Plugin plugin, World world) {
        rule(world, GameRules.ADVANCE_TIME, false);
        rule(world, GameRules.ADVANCE_WEATHER, false);
        rule(world, GameRules.SPAWN_MOBS, false);
        rule(world, GameRules.SPAWN_MONSTERS, false);
        rule(world, GameRules.SPAWN_PATROLS, false);
        rule(world, GameRules.SPAWN_PHANTOMS, false);
        rule(world, GameRules.SPAWN_WANDERING_TRADERS, false);
        rule(world, GameRules.SPAWN_WARDENS, false);
        rule(world, GameRules.SPAWNER_BLOCKS_WORK, false);
        rule(world, GameRules.RAIDS, false);
        rule(world, GameRules.MOB_GRIEFING, false);
        rule(world, GameRules.MOB_DROPS, false);
        rule(world, GameRules.ENTITY_DROPS, false);
        rule(world, GameRules.BLOCK_DROPS, false);
        rule(world, GameRules.KEEP_INVENTORY, true);
        rule(world, GameRules.IMMEDIATE_RESPAWN, true);
        rule(world, GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        rule(world, GameRules.PROJECTILES_CAN_BREAK_BLOCKS, false);
        rule(world, GameRules.TNT_EXPLODES, false);
        rule(world, GameRules.SPREAD_VINES, false);
        rule(world, GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        rule(world, GameRules.RANDOM_TICK_SPEED, 0);

        world.setTime(NOON);
        world.setStorm(false);
        world.setThundering(false);
        // Peaceful would delete the hostile mobs the wave challenge spawns.
        if (world.getDifficulty() == Difficulty.PEACEFUL) {
            plugin.getSLF4JLogger().info("World {} was peaceful; setting it to easy so wave mobs can exist",
                    world.getName());
            world.setDifficulty(Difficulty.EASY);
        }
    }

    private static <T> void rule(World world, GameRule<T> rule, T value) {
        world.setGameRule(rule, value);
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public Location roomOrigin(int sessionSlot, int roomIndex) {
        requireNonNegative("sessionSlot", sessionSlot);
        requireNonNegative("roomIndex", roomIndex);
        return new Location(world, (double) sessionSlot * sessionSpacing, baseY, (double) roomIndex * roomSpacing);
    }

    @Override
    public Location arenaOrigin(int sessionSlot, int pawnSlot) {
        requireNonNegative("sessionSlot", sessionSlot);
        requireNonNegative("pawnSlot", pawnSlot);
        return new Location(world, (double) sessionSlot * sessionSpacing + arenaOffsetX, baseY,
                (double) pawnSlot * ARENA_PITCH_Z);
    }

    private static void requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative, got " + value);
        }
    }

    /** Generates nothing at all: no terrain, caves, decorations, structures or mobs. */
    static final class VoidGenerator extends ChunkGenerator {

        private final int spawnY;

        VoidGenerator(int baseY) {
            this.spawnY = baseY + 1;
        }

        @Override
        public boolean shouldGenerateNoise() {
            return false;
        }

        @Override
        public boolean shouldGenerateSurface() {
            return false;
        }

        @Override
        public boolean shouldGenerateCaves() {
            return false;
        }

        @Override
        public boolean shouldGenerateDecorations() {
            return false;
        }

        @Override
        public boolean shouldGenerateMobs() {
            return false;
        }

        @Override
        public boolean shouldGenerateStructures() {
            return false;
        }

        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            // Without this the server searches the empty world for a safe spawn.
            return new Location(world, 0.5, spawnY, 0.5);
        }
    }

    static final class VoidBiomes extends BiomeProvider {

        @Override
        public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
            return Biome.THE_VOID;
        }

        @Override
        public List<Biome> getBiomes(WorldInfo worldInfo) {
            return List.of(Biome.THE_VOID);
        }
    }
}
