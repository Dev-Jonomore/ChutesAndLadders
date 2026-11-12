package mc.jonomore.chutes.challenge.maze;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mc.jonomore.chutes.ChutesConfig;
import mc.jonomore.chutes.challenge.Challenge;
import mc.jonomore.chutes.challenge.ChallengeContext;
import mc.jonomore.chutes.challenge.ChallengeResult;
import mc.jonomore.chutes.challenge.maze.MazeFloor.Direction;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * A stacked maze for one pawn. The whole structure is built synchronously in {@link #start()}
 * (about 20k block changes with the default config, without physics), so there is no holding
 * phase: players are released into the maze in the same tick and the timer starts then.
 */
final class MazeChallenge implements Challenge {

    /** The arena region reserved for a challenge is this many blocks in +x and +z. */
    static final int ARENA_FOOTPRINT = 128;

    private static final List<Material> WALLS = List.of(
            Material.STONE_BRICKS,
            Material.MOSSY_STONE_BRICKS,
            Material.DEEPSLATE_BRICKS,
            Material.PRISMARINE_BRICKS,
            Material.MUD_BRICKS,
            Material.RED_NETHER_BRICKS,
            Material.END_STONE_BRICKS,
            Material.POLISHED_BLACKSTONE_BRICKS,
            Material.TUFF_BRICKS,
            Material.BRICKS);
    private static final Material SLAB = Material.SMOOTH_STONE;
    private static final Material GOAL = Material.EMERALD_BLOCK;
    private static final int HUD_PERIOD = 10;

    private enum State { NEW, RUNNING, ENDED }

    private final ChallengeContext context;
    private final Plugin plugin;
    private final World world;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final MazeGeometry geometry;
    private final MazeBlueprint blueprint;
    private final long parTicks;
    private final List<UUID> participants;
    private final Map<UUID, Integer> reachedByPlayer = new HashMap<>();
    private final List<long[]> ticketedChunks = new ArrayList<>();
    private final Listener listener = new DamageGuard();
    private final BossBar bossBar;

    private State state = State.NEW;
    /** Highest travel floor any participant has stood on, 0-based. */
    private int bestFloor;
    private boolean completed;
    private long elapsedTicks;
    private boolean blocksPlaced;
    private boolean aborted;
    private BukkitTask task;

    MazeChallenge(ChallengeContext context) {
        this.context = context;
        this.plugin = context.plugin();
        Location origin = context.origin();
        this.world = origin.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("challenge origin has no world");
        }
        this.originX = origin.getBlockX();
        this.originY = origin.getBlockY();
        this.originZ = origin.getBlockZ();

        ChutesConfig.Maze config = context.config().maze();
        int maxCells = MazeGeometry.maxCells(ARENA_FOOTPRINT);
        int cellsX = Math.min(config.cellsWide(), maxCells);
        int cellsZ = Math.min(config.cellsLong(), maxCells);
        int floors = floorCount(context.transit().length(), config);
        int fitting = (world.getMaxHeight() - 1 - originY) / MazeGeometry.LEVEL_HEIGHT;
        floors = Math.max(1, Math.min(floors, fitting));

        this.geometry = new MazeGeometry(cellsX, cellsZ, floors, context.transit().isLadder());
        this.blueprint = new MazeBlueprint(geometry, MazeGenerator.generate(cellsX, cellsZ, floors, context.seed()));
        this.parTicks = (long) floors * config.secondsPerFloor() * 20L;
        this.participants = context.participants().stream().map(Player::getUniqueId).toList();
        this.bossBar = BossBar.bossBar(Component.empty(), 1f,
                geometry.ascending() ? BossBar.Color.GREEN : BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    }

    /** {@code clamp(ceil(length / roomsPerFloor), minFloors, maxFloors)}. */
    static int floorCount(int transitLength, ChutesConfig.Maze config) {
        int floors = Math.ceilDiv(transitLength, config.roomsPerFloor());
        return Math.clamp(floors, config.minFloors(), config.maxFloors());
    }

    @Override
    public void start() {
        if (state != State.NEW) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        try {
            loadChunks();
            build();
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("Failed to build maze at {} {} {}", originX, originY, originZ, e);
            state = State.ENDED;
            cleanup();
            // Report the failure without re-entering the caller of start().
            ChallengeResult result = new ChallengeResult(false, 0, 0, parTicks);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!aborted) {
                    context.onFinish().accept(result);
                }
            });
            return;
        }

        state = State.RUNNING;
        for (UUID id : participants) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                reachedByPlayer.put(id, 0);
                sendTo(player, 0);
                player.showBossBar(bossBar);
            }
        }
        updateHud();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @Override
    public double progress() {
        return completed ? 1.0 : (double) bestFloor / geometry.floors();
    }

    @Override
    public long remainingTicks() {
        return Math.max(0, parTicks - elapsedTicks);
    }

    @Override
    public void abort() {
        aborted = true;
        if (state == State.ENDED) {
            return;
        }
        state = State.ENDED;
        cleanup();
    }

    private void tick() {
        if (state != State.RUNNING) {
            return;
        }
        elapsedTicks++;
        boolean anyOnline = false;
        for (UUID id : participants) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            anyOnline = true;
            if (track(player)) {
                completed = true;
                finish();
                return;
            }
        }
        if (!anyOnline || elapsedTicks >= parTicks) {
            finish();
            return;
        }
        if (elapsedTicks % HUD_PERIOD == 0) {
            updateHud();
            showGoalMarker();
        }
    }

    /** Updates the player's floor, puts them back if they left the structure; true if they reached the goal. */
    private boolean track(Player player) {
        if (!player.getWorld().equals(world)) {
            return false;
        }
        Location at = player.getLocation();
        int dx = at.getBlockX() - originX;
        int dy = at.getBlockY() - originY;
        int dz = at.getBlockZ() - originZ;

        if (!geometry.inside(dx, dy, dz)) {
            // Only reclaim players in our own arena column; anywhere else the game has moved them.
            if (dx >= 0 && dz >= 0 && dx < ARENA_FOOTPRINT && dz < ARENA_FOOTPRINT) {
                sendTo(player, reachedByPlayer.getOrDefault(player.getUniqueId(), 0));
            }
            return false;
        }
        int level = geometry.levelAtFeet(dy);
        if (level < 0) {
            return false;
        }
        int floor = geometry.travelFloor(level);
        int previous = reachedByPlayer.getOrDefault(player.getUniqueId(), 0);
        if (floor > previous) {
            reachedByPlayer.put(player.getUniqueId(), floor);
        }
        if (floor > bestFloor) {
            bestFloor = floor;
            announceFloor();
        }
        return level == blueprint.goalLevel() && geometry.inCell(dx, dz, blueprint.goal());
    }

    private void sendTo(Player player, int travelFloor) {
        int[] spawn = blueprint.spawnOffset(travelFloor);
        Location target = new Location(world,
                originX + spawn[0] + 0.5, originY + spawn[1], originZ + spawn[2] + 0.5,
                yaw(blueprint.openingAtStart(travelFloor)), 0f);
        player.setVelocity(new Vector());
        player.setFallDistance(0f);
        player.teleport(target);
    }

    private static float yaw(Direction direction) {
        return switch (direction) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> -90f;
        };
    }

    private void announceFloor() {
        updateHud();
        forEachOnline(player -> player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f));
    }

    private void updateHud() {
        long seconds = (remainingTicks() + 19) / 20;
        String kind = geometry.ascending() ? "Ladder maze" : "Chute maze";
        Component title = Component.text(kind + "  ", NamedTextColor.WHITE)
                .append(Component.text("Floor " + (bestFloor + 1) + "/" + geometry.floors(), NamedTextColor.YELLOW))
                .append(Component.text(String.format("  %d:%02d", seconds / 60, seconds % 60), NamedTextColor.WHITE));
        bossBar.name(title);
        bossBar.progress(Math.clamp((float) remainingTicks() / parTicks, 0f, 1f));
        forEachOnline(player -> player.showBossBar(bossBar));
    }

    private void showGoalMarker() {
        MazeFloor.Cell goal = blueprint.goal();
        double x = originX + geometry.cellMin(goal.x()) + 1.0;
        double z = originZ + geometry.cellMin(goal.z()) + 1.0;
        double y = originY + geometry.slabY(blueprint.goalLevel()) + 1.5;
        world.spawnParticle(Particle.HAPPY_VILLAGER, x, y, z, 8, 0.5, 0.7, 0.5, 0);
    }

    private void forEachOnline(java.util.function.Consumer<Player> action) {
        for (UUID id : participants) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                action.accept(player);
            }
        }
    }

    private void finish() {
        if (state == State.ENDED) {
            return;
        }
        state = State.ENDED;
        ChallengeResult result = new ChallengeResult(completed, progress(), elapsedTicks, parTicks);
        try {
            cleanup();
        } finally {
            context.onFinish().accept(result);
        }
    }

    // --- world editing ---

    private void loadChunks() {
        int minCx = originX >> 4;
        int minCz = originZ >> 4;
        int maxCx = (originX + geometry.width() - 1) >> 4;
        int maxCz = (originZ + geometry.depth() - 1) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                // Another of this plugin's regions may share the chunk; only release tickets we added.
                if (!chunk.getPluginChunkTickets().contains(plugin) && chunk.addPluginChunkTicket(plugin)) {
                    ticketedChunks.add(new long[] {cx, cz});
                }
            }
        }
    }

    private void build() {
        blocksPlaced = true;
        BlockData slab = SLAB.createBlockData();
        BlockData goal = GOAL.createBlockData();
        BlockData air = Material.AIR.createBlockData();
        Ladder ladder = (Ladder) Material.LADDER.createBlockData();
        ladder.setFacing(BlockFace.WEST);
        Levelled light = (Levelled) Material.LIGHT.createBlockData();
        light.setLevel(light.getMaximumLevel());

        int width = geometry.width();
        int depth = geometry.depth();
        for (int dy = 0; dy < geometry.height(); dy++) {
            BlockData wall = WALLS.get((dy / MazeGeometry.LEVEL_HEIGHT) % WALLS.size()).createBlockData();
            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < depth; dz++) {
                    BlockData data = switch (blueprint.kind(dx, dy, dz)) {
                        case AIR -> air;
                        case SLAB -> slab;
                        case WALL, PILLAR -> wall;
                        case LADDER -> ladder;
                        case LIGHT -> light;
                        case GOAL -> goal;
                    };
                    world.getBlockAt(originX + dx, originY + dy, originZ + dz).setBlockData(data, false);
                }
            }
        }
    }

    private void cleanup() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(listener);
        forEachOnline(player -> player.hideBossBar(bossBar));
        try {
            if (blocksPlaced) {
                clearBlocks();
            }
        } finally {
            for (long[] chunk : ticketedChunks) {
                world.removePluginChunkTicket((int) chunk[0], (int) chunk[1], plugin);
            }
            ticketedChunks.clear();
        }
    }

    private void clearBlocks() {
        blocksPlaced = false;
        int width = geometry.width();
        int depth = geometry.depth();
        // Top down, so nothing is ever left without the block it hangs on.
        for (int dy = geometry.height() - 1; dy >= 0; dy--) {
            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < depth; dz++) {
                    Block block = world.getBlockAt(originX + dx, originY + dy, originZ + dz);
                    if (!block.getType().isAir()) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private final class DamageGuard implements Listener {

        @EventHandler(priority = EventPriority.HIGH)
        public void onDamage(EntityDamageEvent event) {
            if (state != State.ENDED && event.getEntity() instanceof Player player
                    && participants.contains(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }
}
