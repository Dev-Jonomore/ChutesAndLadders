package mc.jonomore.chutes.challenge.waves;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import mc.jonomore.chutes.challenge.Challenge;
import mc.jonomore.chutes.challenge.ChallengeContext;
import mc.jonomore.chutes.challenge.ChallengeResult;
import mc.jonomore.chutes.world.RoomTemplate;
import mc.jonomore.chutes.world.TemplateType;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Waves of mobs in an arena. Kills are counted for every tracked mob that dies or leaves the
 * world for any reason, and a once-a-second sweep catches anything the events missed, so a
 * vanished mob can never stall the challenge.
 */
final class WaveChallenge implements Challenge {

    private static final int BREATHER_TICKS = 50;
    private static final int SWEEP_INTERVAL = 20;
    private static final int BAR_INTERVAL = 5;
    private static final double MOB_FOLLOW_RANGE = 128.0;
    private static final int GLOW_WHEN_LEFT = 2;
    /** Spectators and mobs may stray this far outside the template before being pulled back. */
    private static final double CONFINE_MARGIN = 3.0;
    /** An alive participant this far below the arena floor has fallen out and is eliminated. */
    private static final double FALL_OUT_DEPTH = 16.0;

    private enum Phase { NEW, BREATHER, FIGHTING, ENDED }

    private final ChallengeContext context;
    private final Plugin plugin;
    private final Random random;
    private final RoomTemplate template;
    private final Location origin;
    private final World world;
    private final BoundingBox arenaBox;
    private final BoundingBox confineBox;
    private final Location entrance;
    private final List<Class<? extends Mob>> mobClasses;
    private final List<Vector> spawnOrder;
    private final int total;
    private final int waveSize;
    private final int waveCount;
    private final long limitTicks;

    /** Every participant, online or not, in pawn order. */
    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Set<UUID> alive = new HashSet<>();
    /**
     * Participants whose player state this challenge changed and must restore, with their
     * invulnerability flag from before.
     */
    private final Map<UUID, Boolean> touched = new HashMap<>();
    /** Tracked mobs not yet counted as killed. */
    private final Map<UUID, Mob> mobs = new LinkedHashMap<>();

    private final BossBar bar;
    private final Listener listener = new Events();
    private ArenaChunks chunks;
    private boolean placed;
    private BukkitTask task;

    private Phase phase = Phase.NEW;
    private int startTick;
    private long endElapsed;
    private int breatherLeft;
    private int wave;
    private int spawned;
    private int kills;
    private int nextSpawn;

    WaveChallenge(ChallengeContext context) {
        this.context = context;
        this.plugin = context.plugin();
        this.random = new Random(context.seed());

        List<RoomTemplate> arenas = context.templates().all(TemplateType.ARENA);
        this.template = arenas.get(random.nextInt(arenas.size()));
        this.origin = context.origin().clone();
        this.world = origin.getWorld();

        BlockVector size = template.size();
        this.arenaBox = new BoundingBox(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + size.getX(), origin.getY() + size.getY(), origin.getZ() + size.getZ());
        this.confineBox = arenaBox.clone().expand(CONFINE_MARGIN);
        this.entrance = template.entrance().at(origin);

        List<Vector> spawns = new ArrayList<>(template.mobSpawns());
        if (spawns.isEmpty()) {
            spawns.add(new Vector(size.getX() / 2, template.entrance().offset().getY(), size.getZ() / 2));
        }
        Collections.shuffle(spawns, random);
        this.spawnOrder = List.copyOf(spawns);

        this.mobClasses = mobClasses(context.config().waves().mobTypes());

        var waves = context.config().waves();
        this.total = context.transit().length() * waves.mobsPerRoom();
        this.waveSize = waves.waveSize();
        this.waveCount = (total + waveSize - 1) / waveSize;
        this.limitTicks = (long) total * waves.secondsPerMob() * 20L;

        this.bar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        for (Player player : context.participants()) {
            participants.add(player.getUniqueId());
        }
    }

    private List<Class<? extends Mob>> mobClasses(List<EntityType> types) {
        List<Class<? extends Mob>> classes = new ArrayList<>();
        for (EntityType type : types) {
            Class<? extends Entity> entityClass = type.getEntityClass();
            if (type.isSpawnable() && entityClass != null && Mob.class.isAssignableFrom(entityClass)) {
                classes.add(entityClass.asSubclass(Mob.class));
            } else {
                plugin.getSLF4JLogger().warn("waves.mob-types: {} is not a spawnable mob, skipping it", type);
            }
        }
        if (classes.isEmpty()) {
            classes.add(org.bukkit.entity.Zombie.class);
        }
        return List.copyOf(classes);
    }

    // ---------------------------------------------------------------- Challenge

    @Override
    public void start() {
        if (phase != Phase.NEW) {
            throw new IllegalStateException("wave challenge already started");
        }
        if (world.getDifficulty() == Difficulty.PEACEFUL) {
            plugin.getSLF4JLogger().warn(
                    "World {} is on peaceful difficulty; wave mobs will vanish immediately", world.getName());
        }
        try {
            chunks = ArenaChunks.claim(plugin, world, arenaBox);
            template.place(origin);
            placed = true;
            Bukkit.getPluginManager().registerEvents(listener, plugin);

            for (UUID id : List.copyOf(participants)) {
                WaveChallengeFactory.register(id);
                Player player = Bukkit.getPlayer(id);
                if (player == null) {
                    continue;
                }
                touch(player);
                alive.add(id);
                player.leaveVehicle();
                player.teleport(entrance);
                WaveKit.equip(player);
                player.showBossBar(bar);
            }

            startTick = Bukkit.getCurrentTick();
            startBreather();
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        } catch (RuntimeException e) {
            cleanup();
            throw e;
        }
    }

    @Override
    public double progress() {
        return (double) kills / total;
    }

    @Override
    public long remainingTicks() {
        return Math.max(0L, limitTicks - elapsed());
    }

    @Override
    public void abort() {
        if (phase == Phase.ENDED) {
            return;
        }
        endElapsed = Math.min(elapsed(), limitTicks);
        cleanup();
    }

    // ---------------------------------------------------------------- game loop

    private long elapsed() {
        return switch (phase) {
            case NEW -> 0L;
            case ENDED -> endElapsed;
            default -> Bukkit.getCurrentTick() - startTick;
        };
    }

    private void tick() {
        if (phase == Phase.ENDED) {
            return;
        }
        long elapsed = elapsed();
        if (elapsed >= limitTicks) {
            announceEnd(Component.text("Time's up!", NamedTextColor.RED));
            finish(false);
            return;
        }
        if (elapsed % SWEEP_INTERVAL == 0) {
            sweep();
        }
        if (alive.isEmpty()) {
            announceEnd(Component.text("Everyone is down!", NamedTextColor.RED));
            finish(false);
            return;
        }

        switch (phase) {
            case BREATHER -> {
                if (--breatherLeft <= 0) {
                    spawnWave();
                }
            }
            case FIGHTING -> {
                if (mobs.isEmpty()) {
                    if (kills >= total) {
                        announceEnd(Component.text("Arena cleared!", NamedTextColor.GREEN));
                        finish(true);
                        return;
                    }
                    startBreather();
                } else if (mobs.size() <= GLOW_WHEN_LEFT) {
                    mobs.values().forEach(mob -> mob.setGlowing(true));
                }
            }
            default -> {
            }
        }

        if (elapsed % BAR_INTERVAL == 0) {
            updateBar();
        }
    }

    /** Catches whatever the events missed: vanished mobs, stray mobs and spectators, fallen players. */
    private void sweep() {
        for (Map.Entry<UUID, Mob> entry : List.copyOf(mobs.entrySet())) {
            Mob mob = entry.getValue();
            if (!mob.isValid() || mob.isDead()) {
                markKilled(entry.getKey());
            } else if (!inside(confineBox, mob.getLocation())) {
                mob.teleport(spawnLocation(nextSpawn++));
            } else {
                retarget(mob);
            }
        }
        for (UUID id : List.copyOf(touched.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }
            Location location = player.getLocation();
            if (alive.contains(id)) {
                if (location.getWorld() == world && location.getY() < arenaBox.getMinY() - FALL_OUT_DEPTH) {
                    eliminate(player);
                }
            } else if (!inside(confineBox, location)) {
                player.teleport(entrance);
            }
        }
    }

    private void startBreather() {
        phase = Phase.BREATHER;
        breatherLeft = BREATHER_TICKS;
        int next = wave + 1;
        int count = Math.min(waveSize, total - spawned);
        Component main = next == waveCount && waveCount > 1
                ? Component.text("Final wave", NamedTextColor.RED)
                : Component.text("Wave " + next + "/" + waveCount, NamedTextColor.RED);
        Component sub = Component.text(count + (count == 1 ? " mob" : " mobs") + " incoming", NamedTextColor.GRAY);
        Title title = Title.title(main, sub,
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1750), Duration.ofMillis(500)));
        for (Player player : onlineTouched()) {
            player.showTitle(title);
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.8f);
        }
        updateBar();
    }

    private void spawnWave() {
        wave++;
        int count = Math.min(waveSize, total - spawned);
        for (int i = 0; i < count; i++) {
            Location location = spawnLocation(nextSpawn++);
            Class<? extends Mob> type = mobClasses.get(random.nextInt(mobClasses.size()));
            spawnMob(location, type);
        }
        phase = Phase.FIGHTING;
        for (Player player : onlineTouched()) {
            player.playSound(player, Sound.ENTITY_WITHER_SPAWN, 0.4f, 1.4f);
        }
        updateBar();
    }

    private void spawnMob(Location location, Class<? extends Mob> type) {
        spawned++;
        // Tracked inside the consumer, before the mob joins the world, so the spawn listener
        // can tell it apart from jockeys and other extras spawned alongside it.
        Mob mob = world.spawn(location, type, true, created -> {
            mobs.put(created.getUniqueId(), created);
            configure(created);
        });
        if (!mob.isValid()) {
            // Spawn cancelled by someone else: count it, never wait for it.
            markKilled(mob.getUniqueId());
            return;
        }
        for (Entity passenger : List.copyOf(mob.getPassengers())) {
            passenger.remove();
        }
        Entity vehicle = mob.getVehicle();
        if (vehicle != null) {
            mob.leaveVehicle();
            vehicle.remove();
        }
        configure(mob);
        retarget(mob);
    }

    private void configure(Mob mob) {
        mob.setRemoveWhenFarAway(false);
        mob.setPersistent(false);
        mob.setCanPickupItems(false);
        if (mob instanceof Ageable ageable) {
            ageable.setAdult();
        }
        AttributeInstance followRange = mob.getAttribute(Attribute.FOLLOW_RANGE);
        if (followRange != null && followRange.getBaseValue() < MOB_FOLLOW_RANGE) {
            followRange.setBaseValue(MOB_FOLLOW_RANGE);
        }
    }

    private void retarget(Mob mob) {
        LivingEntity target = mob.getTarget();
        if (target == null || !isAlive(target)) {
            mob.setTarget(nearestAlive(mob.getLocation()));
        }
    }

    private void markKilled(UUID mobId) {
        if (mobs.remove(mobId) != null) {
            kills++;
        }
    }

    private void finish(boolean completed) {
        if (phase == Phase.ENDED) {
            return;
        }
        endElapsed = Math.min(elapsed(), limitTicks);
        double progress = completed ? 1.0 : progress();
        cleanup();
        context.onFinish().accept(new ChallengeResult(completed, progress, endElapsed, limitTicks));
    }

    /** Removes everything this challenge made and restores participants. Idempotent. */
    private void cleanup() {
        phase = Phase.ENDED;
        HandlerList.unregisterAll(listener);
        if (task != null) {
            task.cancel();
            task = null;
        }

        for (Mob mob : mobs.values()) {
            mob.remove();
        }
        mobs.clear();
        if (placed) {
            for (Entity entity : world.getNearbyEntities(arenaBox, entity -> !(entity instanceof Player))) {
                entity.remove();
            }
        }

        for (UUID id : List.copyOf(participants)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && touched.containsKey(id)) {
                restore(player);
            }
            WaveChallengeFactory.unregister(id);
        }
        touched.clear();
        alive.clear();

        if (placed) {
            placed = false;
            template.clear(origin);
        }
        if (chunks != null) {
            chunks.release();
            chunks = null;
        }
    }

    // ---------------------------------------------------------------- participants

    private void touch(Player player) {
        touched.putIfAbsent(player.getUniqueId(), player.isMarkedInvulnerable());
        player.setInvulnerable(false);
    }

    /** Back to adventure mode with an empty inventory, healed, as it was before the challenge. */
    private void restore(Player player) {
        player.hideBossBar(bar);
        player.setGameMode(GameMode.ADVENTURE);
        WaveKit.reset(player);
        Boolean wasInvulnerable = touched.remove(player.getUniqueId());
        player.setInvulnerable(wasInvulnerable != null && wasInvulnerable);
    }

    /** Puts a participant out: spectator at the arena until the challenge ends. */
    private void eliminate(Player player) {
        if (!alive.remove(player.getUniqueId())) {
            return;
        }
        WaveKit.reset(player);
        player.setGameMode(GameMode.SPECTATOR);
        if (!inside(confineBox, player.getLocation())) {
            player.teleport(entrance);
        }
        player.showTitle(Title.title(
                Component.text("You are out", NamedTextColor.RED),
                Component.text("Spectating until the fight ends", NamedTextColor.GRAY)));
        player.playSound(player, Sound.ENTITY_PLAYER_HURT, 1.0f, 0.6f);
        Component notice = Component.text(player.getName() + " is out!", NamedTextColor.RED);
        for (Player other : onlineTouched()) {
            if (other != player) {
                other.sendMessage(notice);
            }
        }
    }

    private void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        alive.remove(id);
        // Restore now: a player who is offline when the challenge ends cannot be reset then.
        if (touched.containsKey(id)) {
            restore(player);
        }
    }

    /**
     * Someone else moved a participant out of the arena (the game removing one pawn member, an
     * admin teleport). Let go of them without touching inventory or game mode, which whoever
     * moved them now owns; they count as out.
     */
    private void release(Player player) {
        UUID id = player.getUniqueId();
        participants.remove(id);
        alive.remove(id);
        Boolean wasInvulnerable = touched.remove(id);
        player.hideBossBar(bar);
        player.setInvulnerable(wasInvulnerable != null && wasInvulnerable);
        WaveChallengeFactory.unregister(id);
    }

    /** A participant who quit and came back stays out: spectator at the arena. */
    private void handleRejoin(UUID id) {
        if (phase == Phase.ENDED) {
            return;
        }
        Player player = Bukkit.getPlayer(id);
        if (player == null || !participants.contains(id) || alive.contains(id)) {
            return;
        }
        touch(player);
        WaveKit.reset(player);
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(entrance);
        player.showBossBar(bar);
    }

    private boolean isAlive(Entity entity) {
        return entity instanceof Player player && alive.contains(player.getUniqueId()) && player.isOnline();
    }

    private Player nearestAlive(Location from) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (UUID id : alive) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || player.getWorld() != from.getWorld()) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(from);
            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private List<Player> onlineTouched() {
        List<Player> players = new ArrayList<>();
        for (UUID id : touched.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                players.add(player);
            }
        }
        return players;
    }

    // ---------------------------------------------------------------- HUD

    private void updateBar() {
        long remaining = remainingTicks();
        long seconds = (remaining + 19) / 20;
        String text = "Wave " + Math.max(wave, 1) + "/" + waveCount
                + "  ·  Kills " + kills + "/" + total
                + "  ·  " + seconds / 60 + ":" + String.format("%02d", seconds % 60);
        bar.name(Component.text(text, NamedTextColor.WHITE));
        bar.progress((float) Math.clamp((double) remaining / limitTicks, 0.0, 1.0));
    }

    private void announceEnd(Component message) {
        Title title = Title.title(message, Component.empty());
        for (Player player : onlineTouched()) {
            player.showTitle(title);
        }
    }

    // ---------------------------------------------------------------- geometry

    private Location spawnLocation(int index) {
        return origin.clone().add(spawnOrder.get(Math.floorMod(index, spawnOrder.size())));
    }

    private boolean inside(BoundingBox box, Location location) {
        return location.getWorld() == world && box.contains(location.toVector());
    }

    // ---------------------------------------------------------------- events

    /** Only reacts to this instance's participants and mobs. */
    private final class Events implements Listener {

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onFriendlyFire(EntityDamageByEntityEvent event) {
            if (event.getEntity() instanceof Player victim && participants.contains(victim.getUniqueId())) {
                Player attacker = attackingPlayer(event.getDamager());
                if (attacker != null && participants.contains(attacker.getUniqueId())) {
                    event.setCancelled(true);
                }
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onDamage(EntityDamageEvent event) {
            if (!(event.getEntity() instanceof Player player) || !participants.contains(player.getUniqueId())) {
                return;
            }
            if (!alive.contains(player.getUniqueId())) {
                event.setCancelled(true);
            } else if (event.getFinalDamage() >= player.getHealth()) {
                event.setCancelled(true);
                eliminate(player);
            }
        }

        /** Backstop for deaths that bypass damage events, e.g. health set to 0 by a command. */
        @EventHandler(priority = EventPriority.LOWEST)
        public void onPlayerDeath(PlayerDeathEvent event) {
            Player player = event.getPlayer();
            if (!participants.contains(player.getUniqueId())) {
                return;
            }
            event.setCancelled(true);
            event.getDrops().clear();
            event.setDroppedExp(0);
            event.setKeepInventory(true);
            event.setReviveHealth(1.0);
            if (alive.contains(player.getUniqueId())) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (phase != Phase.ENDED && player.isOnline()) {
                        eliminate(player);
                    }
                });
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onMobDeath(EntityDeathEvent event) {
            UUID id = event.getEntity().getUniqueId();
            if (mobs.containsKey(id)) {
                event.getDrops().clear();
                event.setDroppedExp(0);
                markKilled(id);
            }
        }

        @EventHandler
        public void onMobRemoved(EntityRemoveFromWorldEvent event) {
            markKilled(event.getEntity().getUniqueId());
        }

        /** Jockeys, reinforcements, slime splits: nothing joins the arena untracked. */
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onCreatureSpawn(CreatureSpawnEvent event) {
            if (event.getEntity() instanceof Mob mob
                    && !mobs.containsKey(mob.getUniqueId())
                    && inside(confineBox, event.getLocation())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onTarget(EntityTargetEvent event) {
            if (!mobs.containsKey(event.getEntity().getUniqueId())) {
                return;
            }
            Entity target = event.getTarget();
            if (target != null && !isAlive(target)) {
                Player nearest = nearestAlive(event.getEntity().getLocation());
                if (nearest != null) {
                    event.setTarget(nearest);
                } else {
                    event.setCancelled(true);
                }
            }
        }

        /** Undead burning in daylight would steal kills from the players. */
        @EventHandler(ignoreCancelled = true)
        public void onCombust(EntityCombustEvent event) {
            if (event.getClass() == EntityCombustEvent.class && mobs.containsKey(event.getEntity().getUniqueId())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onExplode(EntityExplodeEvent event) {
            if (mobs.containsKey(event.getEntity().getUniqueId())) {
                event.blockList().clear();
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onDrop(PlayerDropItemEvent event) {
            if (participants.contains(event.getPlayer().getUniqueId())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onSpectatorMove(PlayerMoveEvent event) {
            UUID id = event.getPlayer().getUniqueId();
            if (participants.contains(id) && !alive.contains(id)
                    && event.getTo().getWorld() == world && !confineBox.contains(event.getTo().toVector())) {
                event.setTo(entrance.clone());
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onSpectatorTeleport(PlayerTeleportEvent event) {
            UUID id = event.getPlayer().getUniqueId();
            if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE
                    && participants.contains(id) && !alive.contains(id)
                    && !inside(confineBox, event.getTo())) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onTeleportedAway(PlayerTeleportEvent event) {
            if (touched.containsKey(event.getPlayer().getUniqueId()) && !inside(confineBox, event.getTo())) {
                release(event.getPlayer());
            }
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            if (participants.contains(event.getPlayer().getUniqueId())) {
                handleQuit(event.getPlayer());
            }
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            UUID id = event.getPlayer().getUniqueId();
            if (participants.contains(id)) {
                // A tick later, so the game layer's own join handling has run first.
                Bukkit.getScheduler().runTask(plugin, () -> handleRejoin(id));
            }
        }

        private static Player attackingPlayer(Entity damager) {
            if (damager instanceof Player player) {
                return player;
            }
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
                return shooter;
            }
            return null;
        }
    }
}
