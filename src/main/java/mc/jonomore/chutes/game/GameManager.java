package mc.jonomore.chutes.game;

import static net.kyori.adventure.text.Component.text;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import mc.jonomore.chutes.ChutesConfig;
import mc.jonomore.chutes.Services;
import mc.jonomore.chutes.board.Board;
import mc.jonomore.chutes.board.BoardRoom;
import mc.jonomore.chutes.board.ChallengeType;
import mc.jonomore.chutes.board.RoomKind;
import mc.jonomore.chutes.board.Transit;
import mc.jonomore.chutes.challenge.Challenge;
import mc.jonomore.chutes.challenge.ChallengeContext;
import mc.jonomore.chutes.challenge.ChallengeFactory;
import mc.jonomore.chutes.challenge.ChallengeResult;
import mc.jonomore.chutes.stats.RunRecord;
import mc.jonomore.chutes.stats.StatsStore;
import mc.jonomore.chutes.world.ButtonSpot;
import mc.jonomore.chutes.world.RoomTemplate;
import mc.jonomore.chutes.world.TemplateType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockVector;
import org.jspecify.annotations.Nullable;

/**
 * Owns every game session: lobbies, board building, pawn movement, transits, finishing and
 * cleanup. All methods run on the main thread.
 */
public final class GameManager {

    public static final int MIN_ROOMS = 5;
    public static final int MAX_ROOMS = 500;
    static final int MAX_PAWNS = 16;
    static final String ADMIN_PERMISSION = "chutes.admin";

    private static final long TICK_PERIOD = 5;
    private static final long END_DELAY_TICKS = 100;
    private static final long PRESS_COOLDOWN_TICKS = 4;

    private final Plugin plugin;
    private final Services services;
    private final ChutesConfig config;
    private final SnapshotStore snapshots;
    private final List<GameSession> sessions = new ArrayList<>();
    private final Map<UUID, GameSession> byPlayer = new HashMap<>();
    /** Interaction entity UUID to the pawn whose exit button it is. */
    private final Map<UUID, Pawn> buttonOwners = new HashMap<>();
    private final Random dice = new Random();
    private final BukkitTask ticker;

    private TransitResolver resolver = DefaultTransitScoring::roomsMoved;
    private long tickCounter;
    private boolean shutDown;

    public GameManager(Plugin plugin, Services services) {
        this.plugin = plugin;
        this.services = services;
        this.config = services.config();
        this.snapshots = new SnapshotStore(plugin);
        plugin.getServer().getPluginManager().registerEvents(new GameListener(this), plugin);
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);
        // After a reload or crash, anyone online with a saved state is no longer in a game.
        for (Player player : Bukkit.getOnlinePlayers()) {
            restorePending(player);
        }
    }

    /** Replace the built-in scoring table, e.g. with {@code TransitScoring::roomsMoved}. */
    public void setTransitResolver(TransitResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver);
    }

    /** Ends every session immediately and synchronously, restoring every online player. */
    public void shutdown() {
        if (shutDown) {
            return;
        }
        shutDown = true;
        ticker.cancel();
        for (GameSession session : List.copyOf(sessions)) {
            try {
                endSession(session, true, true);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().error("Failed to end a game cleanly on shutdown", e);
            }
        }
        sessions.clear();
        byPlayer.clear();
        buttonOwners.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            restorePending(player);
        }
    }

    ChutesConfig config() {
        return config;
    }

    StatsStore stats() {
        return services.stats();
    }

    // ------------------------------------------------------------------ lobby commands

    void create(Player host, @Nullable Integer rooms, @Nullable Long seed) {
        GameSession session = createSession(host, rooms, seed);
        host.sendMessage(GameText.info(text("Lobby open: " + session.rooms + " rooms. Others can join with ")
                .append(text("/chutes join " + host.getName(), NamedTextColor.WHITE))
                .append(text(", then start with "))
                .append(text("/chutes start", NamedTextColor.WHITE))
                .append(text("."))));
    }

    void solo(Player player, @Nullable Integer rooms, @Nullable Long seed) {
        GameSession session = createSession(player, rooms, seed);
        try {
            startSession(session);
        } catch (RuntimeException e) {
            endSession(session, true, false);
            throw e;
        }
    }

    void join(Player player, String hostName) {
        requireFree(player);
        Player host = Bukkit.getPlayerExact(hostName);
        GameSession session = host == null ? null : byPlayer.get(host.getUniqueId());
        if (host == null || session == null || !session.host.equals(host.getUniqueId())) {
            throw new GameException(hostName + " is not hosting a game.");
        }
        if (session.state != SessionState.LOBBY) {
            throw new GameException("That game has already started.");
        }
        String team = player.getName();
        List<UUID> existing = session.teams.get(team);
        if (existing != null && existing.size() >= config.session().maxTeamSize()) {
            team = uniqueTeamName(session, team);
            existing = null;
        }
        if (existing == null && session.teams.size() >= MAX_PAWNS) {
            throw new GameException("That game is full (" + MAX_PAWNS + " teams).");
        }
        session.names.put(player.getUniqueId(), player.getName());
        session.addToTeam(player.getUniqueId(), team);
        byPlayer.put(player.getUniqueId(), session);
        broadcast(session, GameText.info(player.getName() + " joined the lobby (team " + team + ")."));
    }

    void team(Player player, String name) {
        GameSession session = requireSession(player);
        if (session.state != SessionState.LOBBY) {
            throw new GameException("Teams can only be changed in the lobby.");
        }
        UUID id = player.getUniqueId();
        String current = session.teamOf(id);
        if (name.equals(current)) {
            throw new GameException("You are already on team " + name + ".");
        }
        List<UUID> target = session.teams.get(name);
        if (target != null && target.size() >= config.session().maxTeamSize()) {
            throw new GameException("Team " + name + " is full (" + config.session().maxTeamSize() + " players).");
        }
        boolean leavesTeamEmpty = current != null && session.teams.get(current).size() == 1;
        if (target == null && !leavesTeamEmpty && session.teams.size() >= MAX_PAWNS) {
            throw new GameException("There can be at most " + MAX_PAWNS + " teams.");
        }
        session.removeFromTeams(id);
        session.addToTeam(id, name);
        broadcast(session, GameText.info(player.getName() + " joined team " + name + "."));
    }

    void leave(Player player) {
        UUID id = player.getUniqueId();
        GameSession session = requireSession(player);
        switch (session.state) {
            case LOBBY -> {
                if (id.equals(session.host)) {
                    broadcast(session, GameText.info("The host closed the lobby."));
                    endSession(session, true, false);
                } else {
                    session.removeFromTeams(id);
                    byPlayer.remove(id, session);
                    player.sendMessage(GameText.info("You left the lobby."));
                    broadcast(session, GameText.info(player.getName() + " left the lobby."));
                }
            }
            case STARTING -> {
                session.removeFromTeams(id);
                byPlayer.remove(id, session);
                player.sendMessage(GameText.info("You left the game."));
                if (session.teams.isEmpty()) {
                    endSession(session, true, false);
                } else {
                    transferHost(session);
                }
            }
            case RUNNING -> {
                Pawn pawn = session.pawnOf(id);
                if (pawn == null) {
                    byPlayer.remove(id, session);
                    snapshots.restore(player);
                    return;
                }
                if (pawn.isParticipant(id) && pawn.members.size() > 1) {
                    // The challenge owns this player's inventory and game mode until it ends.
                    pawn.pendingLeaves.add(id);
                    player.sendMessage(GameText.info("You will leave as soon as this challenge ends."));
                    return;
                }
                removeMember(session, pawn, id);
                player.sendMessage(GameText.info("You left the game."));
            }
            case ENDING, CLOSED -> {
                byPlayer.remove(id, session);
                snapshots.restore(player);
            }
        }
    }

    void start(Player player) {
        GameSession session = requireSession(player);
        if (!session.host.equals(player.getUniqueId()) && !player.hasPermission(ADMIN_PERMISSION)) {
            throw new GameException("Only the host can start the game.");
        }
        startSession(session);
    }

    void stop(CommandSender sender, @Nullable String targetName) {
        GameSession session;
        if (targetName != null) {
            Player target = Bukkit.getPlayerExact(targetName);
            session = target == null ? null : byPlayer.get(target.getUniqueId());
            if (session == null) {
                throw new GameException(targetName + " is not in a game.");
            }
            if (!(sender instanceof Player player && session.host.equals(player.getUniqueId()))
                    && !sender.hasPermission(ADMIN_PERMISSION)) {
                throw new GameException("You may only stop your own game.");
            }
        } else {
            if (!(sender instanceof Player player)) {
                throw new GameException("Name a player whose game to stop.");
            }
            session = requireSession(player);
            if (!session.host.equals(player.getUniqueId()) && !sender.hasPermission(ADMIN_PERMISSION)) {
                throw new GameException("Only the host can stop the game.");
            }
        }
        boolean senderInside = sender instanceof Player player && session.members().contains(player.getUniqueId());
        broadcast(session, GameText.info("The game was stopped by " + sender.getName() + "."));
        endSession(session, true, false);
        if (!senderInside) {
            sender.sendMessage(GameText.info("Game stopped."));
        }
    }

    /** Board size of the player's game, or the default. */
    int roomsFor(Player player) {
        GameSession session = byPlayer.get(player.getUniqueId());
        return session == null ? config.board().defaultRooms() : session.rooms;
    }

    /** Size of the player's team, or 1. */
    int teamSizeFor(Player player) {
        GameSession session = byPlayer.get(player.getUniqueId());
        if (session == null) {
            return 1;
        }
        Pawn pawn = session.pawnOf(player.getUniqueId());
        if (pawn != null) {
            return pawn.members.size();
        }
        String team = session.teamOf(player.getUniqueId());
        return team == null ? 1 : session.teams.get(team).size();
    }

    /** Names of players hosting an open lobby, for command suggestions. */
    List<String> lobbyHosts() {
        List<String> hosts = new ArrayList<>();
        for (GameSession session : sessions) {
            if (session.state == SessionState.LOBBY) {
                Player host = Bukkit.getPlayer(session.host);
                if (host != null) {
                    hosts.add(host.getName());
                }
            }
        }
        return hosts;
    }

    private GameSession createSession(Player host, @Nullable Integer rooms, @Nullable Long seed) {
        requireFree(host);
        int size = rooms == null ? config.board().defaultRooms() : rooms;
        if (size < MIN_ROOMS || size > MAX_ROOMS) {
            throw new GameException("A board has " + MIN_ROOMS + " to " + MAX_ROOMS + " rooms.");
        }
        long boardSeed = seed == null ? ThreadLocalRandom.current().nextLong() : seed;
        GameSession session = new GameSession(host.getUniqueId(), host.getName(), size, boardSeed);
        sessions.add(session);
        byPlayer.put(host.getUniqueId(), session);
        return session;
    }

    private void requireFree(Player player) {
        if (byPlayer.containsKey(player.getUniqueId())) {
            throw new GameException("You are already in a game. Use /chutes leave first.");
        }
    }

    private GameSession requireSession(Player player) {
        GameSession session = byPlayer.get(player.getUniqueId());
        if (session == null) {
            throw new GameException("You are not in a game.");
        }
        return session;
    }

    private static String uniqueTeamName(GameSession session, String base) {
        for (int i = 2; ; i++) {
            String candidate = base + i;
            if (!session.teams.containsKey(candidate)) {
                return candidate;
            }
        }
    }

    private void transferHost(GameSession session) {
        Set<UUID> members = session.members();
        if (!members.contains(session.host) && !members.isEmpty()) {
            session.host = members.iterator().next();
            broadcast(session, GameText.info(session.nameOf(session.host) + " is now the host."));
        }
    }

    // ------------------------------------------------------------------ starting

    private void startSession(GameSession session) {
        if (session.state != SessionState.LOBBY) {
            throw new GameException("The game has already started.");
        }
        Board board;
        List<RoomTemplate> templates;
        try {
            board = services.boardGenerator().generate(config.board(), session.rooms, session.seed);
            templates = chooseTemplates(board, session.seed);
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("Could not set up a board of {} rooms with seed {}", session.rooms, session.seed, e);
            throw new GameException("Could not set up the board; see the server log.");
        }
        int slot = freeSlot();
        session.slot = slot;
        session.board = board;
        session.roomTemplates = templates;
        session.state = SessionState.STARTING;

        World world = services.boardWorld().world();
        List<BoardBuilder.Region> regions = new ArrayList<>(board.size() + 1);
        for (int index = 0; index < board.size(); index++) {
            RoomTemplate template = templates.get(index);
            Location origin = roomOrigin(session, index);
            BlockVector size = template.size();
            regions.add(new BoardBuilder.Region(origin, size.getBlockX(), size.getBlockZ(),
                    () -> template.place(origin), () -> template.clear(origin)));
        }
        Location finish = finishOrigin(session);
        regions.add(new BoardBuilder.Region(finish, FinishPlatform.SIZE, FinishPlatform.SIZE,
                () -> FinishPlatform.place(finish), () -> FinishPlatform.clear(finish)));

        BoardBuilder builder = new BoardBuilder(plugin, world, regions);
        session.builder = builder;
        broadcast(session, GameText.info("Building a board of " + board.size() + " rooms (seed " + session.seed + ")..."));
        builder.build((placed, total) -> {
            Component bar = text("Building the board: " + placed + " / " + total, NamedTextColor.YELLOW);
            forEachOnline(session.members(), player -> player.sendActionBar(bar));
        }, () -> begin(session));
    }

    private List<RoomTemplate> chooseTemplates(Board board, long seed) {
        List<RoomTemplate> rooms = services.templates().all(TemplateType.ROOM);
        List<RoomTemplate> breaks = services.templates().all(TemplateType.BREAK);
        if (rooms.isEmpty() || breaks.isEmpty()) {
            throw new IllegalStateException("the template store returned no ROOM or BREAK templates");
        }
        List<RoomTemplate> ladders = orElse(services.templates().all(TemplateType.LADDER), rooms);
        List<RoomTemplate> chutes = orElse(services.templates().all(TemplateType.CHUTE), rooms);
        List<RoomTemplate> chosen = new ArrayList<>(board.size());
        for (int index = 0; index < board.size(); index++) {
            List<RoomTemplate> pool = switch (board.room(index).kind()) {
                case BREAK -> breaks;
                case LADDER -> ladders;
                case CHUTE -> chutes;
                case NORMAL -> rooms;
            };
            Random random = new Random(MovementRules.derivedSeed(seed, index, MovementRules.TEMPLATE_SALT));
            chosen.add(pool.get(random.nextInt(pool.size())));
        }
        return chosen;
    }

    private static List<RoomTemplate> orElse(List<RoomTemplate> preferred, List<RoomTemplate> fallback) {
        return preferred.isEmpty() ? fallback : preferred;
    }

    private int freeSlot() {
        Set<Integer> used = new TreeSet<>();
        for (GameSession session : sessions) {
            if (session.slot >= 0) {
                used.add(session.slot);
            }
        }
        int slot = 0;
        while (used.contains(slot)) {
            slot++;
        }
        return slot;
    }

    private void begin(GameSession session) {
        if (session.state != SessionState.STARTING) {
            return;
        }
        session.state = SessionState.RUNNING;
        session.startTick = now();
        int pawnSlot = 0;
        for (Map.Entry<String, List<UUID>> team : session.teams.entrySet()) {
            Pawn pawn = new Pawn(session, pawnSlot++, team.getKey());
            for (UUID member : team.getValue()) {
                pawn.members.put(member, session.nameOf(member));
            }
            session.pawns.add(pawn);
        }
        broadcast(session, GameText.info("Go! First team to room " + session.board().size() + " wins."));
        for (Pawn pawn : session.pawns) {
            for (Player player : pawn.onlineMembers()) {
                enterGame(player);
            }
            arrive(session, pawn, 0, Component.empty(), false);
        }
        if (!session.hasActivePawns()) {
            endSession(session, true, false);
        }
    }

    // ------------------------------------------------------------------ rooms and buttons

    private void arrive(GameSession session, Pawn pawn, int index, Component note, boolean afterTransit) {
        removeButton(pawn);
        Board board = session.board();
        if (MovementRules.isFinish(index, board.size())) {
            finishPawn(session, pawn);
            return;
        }
        pawn.roomIndex = index;
        BoardRoom room = board.room(index);
        RoomTemplate template = session.roomTemplates.get(index);
        Location origin = roomOrigin(session, index);
        boolean rest = room.kind() == RoomKind.BREAK;
        pawn.checkpoint = MovementRules.checkpointAfterArrival(pawn.checkpoint, index, rest, config.board().breakCheckpoint());

        Location entrance = template.entrance().at(origin);
        Title title = GameText.roomTitle(room, board.size(), note);
        for (Player player : pawn.onlineMembers()) {
            if (afterTransit) {
                resetForGame(player, false);
            }
            if (rest) {
                heal(player);
            }
            player.teleport(entrance);
            player.setFallDistance(0);
            player.showTitle(title);
            playRoomSound(player, room.kind());
        }
        Transit transit = room.transit();
        if (transit != null) {
            // Ladder and chute squares have no button: the pawn lands, and the challenge begins.
            animate(session, pawn, done -> ExitAnimations.discovery(plugin, pawn::onlineMembers, transit, done),
                    () -> startTransit(session, pawn, room));
            return;
        }
        spawnButton(pawn, template, origin, rest);
    }

    private void spawnButton(Pawn pawn, RoomTemplate template, Location origin, boolean rest) {
        List<ButtonSpot> spots = template.buttonSpots();
        ButtonSpot spot;
        if (spots.isEmpty()) {
            // Contract says at least one; fall back to a floor button at the entrance so the pawn is never stuck.
            plugin.getSLF4JLogger().warn("Template {} has no button spots", template.id());
            var offset = template.entrance().offset();
            spot = new ButtonSpot(new BlockVector(offset.getBlockX(), offset.getBlockY() - 1, offset.getBlockZ()), BlockFace.UP);
        } else {
            spot = rest ? spots.getFirst() : spots.get(dice.nextInt(spots.size()));
        }
        ExitButton button = ExitButton.spawn(origin, spot, rest);
        pawn.button = button;
        buttonOwners.put(button.interactionId(), pawn);
        for (Player player : pawn.onlineMembers()) {
            button.showTo(plugin, player);
        }
    }

    private void removeButton(Pawn pawn) {
        ExitButton button = pawn.button;
        if (button != null) {
            pawn.button = null;
            buttonOwners.remove(button.interactionId());
            button.remove();
        }
    }

    /**
     * A click (either button) on an entity. Returns true when the entity is an exit button, so
     * the caller cancels the event; presses by non-members are swallowed.
     */
    boolean clickButton(Player player, Entity entity) {
        Pawn pawn = buttonOwners.get(entity.getUniqueId());
        if (pawn == null) {
            return false;
        }
        GameSession session = pawn.session;
        if (!pawn.isMember(player.getUniqueId()) || session.state != SessionState.RUNNING
                || !pawn.active() || pawn.transit != null) {
            return true;
        }
        long now = now();
        // Some clicks raise several events in the same tick; only the first counts.
        if (now - pawn.lastPressTick < PRESS_COOLDOWN_TICKS) {
            return true;
        }
        pawn.lastPressTick = now;
        try {
            pressExit(session, pawn, player);
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("Exit press failed for pawn {}", pawn.name, e);
        }
        return true;
    }

    private void pressExit(GameSession session, Pawn pawn, Player presser) {
        removeButton(pawn);
        pawn.plies++;
        BoardRoom room = session.board().room(pawn.roomIndex);
        switch (room.kind()) {
            case NORMAL, BREAK -> {
                int roll = MovementRules.roll(dice, config.board().maxRoll());
                int from = pawn.roomIndex;
                int destination = MovementRules.afterRoll(from, roll, session.board().size());
                String roller = presser.getName();
                Component note = text(roller + " rolled " + roll, NamedTextColor.GOLD);
                clickSound(pawn);
                animate(session, pawn, done -> ExitAnimations.roll(plugin, pawn::onlineMembers, dice,
                        config.board().maxRoll(), roll, roller, done), () -> {
                    for (Player player : pawn.onlineMembers()) {
                        player.sendMessage(GameText.info(roller + " rolled a " + roll + ": room "
                                + (from + 1) + " -> " + (MovementRules.isFinish(destination, session.board().size())
                                        ? "finish" : String.valueOf(destination + 1))));
                    }
                    arrive(session, pawn, destination, note, false);
                });
            }
            case LADDER, CHUTE -> {
                Transit transit = Objects.requireNonNull(room.transit(), "transit room without transit");
                clickSound(pawn);
                animate(session, pawn, done -> ExitAnimations.discovery(plugin, pawn::onlineMembers, transit, done),
                        () -> startTransit(session, pawn, room));
            }
        }
    }

    private static void clickSound(Pawn pawn) {
        for (Player player : pawn.onlineMembers()) {
            player.playSound(player.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1f, 1f);
        }
    }

    /**
     * Plays an exit animation for a pawn, then runs {@code then} if the pawn is still in play.
     * The pawn has no button while it runs, so it cannot press again; {@link #dismantle} cancels it.
     */
    private void animate(GameSession session, Pawn pawn, Function<Runnable, BukkitTask> animation, Runnable then) {
        BukkitTask[] started = new BukkitTask[1];
        Runnable done = () -> {
            if (pawn.animation != started[0]) {
                return;
            }
            pawn.animation = null;
            if (session.state != SessionState.RUNNING || !pawn.active() || pawn.transit != null) {
                return;
            }
            try {
                then.run();
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().error("Exit failed for pawn {}", pawn.name, e);
            }
        };
        started[0] = animation.apply(done);
        pawn.animation = started[0];
    }

    // ------------------------------------------------------------------ transits

    private void startTransit(GameSession session, Pawn pawn, BoardRoom room) {
        Transit transit = Objects.requireNonNull(room.transit(), "transit room without transit");
        List<Player> online = pawn.onlineMembers();
        ChallengeFactory factory = factoryFor(transit.challenge());
        ChallengeType type = factory == null ? transit.challenge() : factory.type();
        Set<UUID> ids = new HashSet<>();
        online.forEach(player -> ids.add(player.getUniqueId()));
        ActiveTransit active = new ActiveTransit(++pawn.transitCounter, pawn.roomIndex, transit, type, ids);
        pawn.transit = active;

        Title title = GameText.transitStartTitle(transit, type);
        for (Player player : online) {
            player.showTitle(title);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        }
        if (factory == null) {
            plugin.getSLF4JLogger().warn("No challenge factory is registered; transit counts as failed");
            completeTransit(session, pawn, active, new ChallengeResult(false, 0, 0, 1));
            return;
        }
        int token = active.token;
        long seed = MovementRules.derivedSeed(session.seed, pawn.roomIndex, MovementRules.CHALLENGE_SALT);
        ChallengeContext context = new ChallengeContext(plugin, config, services.templates(), online,
                services.boardWorld().arenaOrigin(session.slot, pawn.slot).clone(), transit, seed,
                result -> challengeFinished(pawn, token, result));
        try {
            Challenge challenge = factory.create(context);
            active.challenge = challenge;
            challenge.start();
            startMusic(active);
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("{} challenge failed to start", type, e);
            if (pawn.transit == active) {
                Challenge challenge = active.challenge;
                active.challenge = null;
                if (challenge != null) {
                    abortQuietly(challenge);
                }
                completeTransit(session, pawn, active, new ChallengeResult(false, 0, 0, 1));
            }
        }
    }

    private @Nullable ChallengeFactory factoryFor(ChallengeType type) {
        Map<ChallengeType, ChallengeFactory> factories = services.challenges();
        ChallengeFactory factory = factories.get(type);
        if (factory != null) {
            return factory;
        }
        for (ChallengeType other : ChallengeType.values()) {
            factory = factories.get(other);
            if (factory != null) {
                return factory;
            }
        }
        return null;
    }

    private void challengeFinished(Pawn pawn, int token, ChallengeResult result) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> challengeFinished(pawn, token, result));
            return;
        }
        ActiveTransit active = pawn.transit;
        // Late, duplicate, or for a pawn/session that has since been torn down.
        if (active == null || active.token != token || pawn.removed || pawn.session.state != SessionState.RUNNING) {
            return;
        }
        try {
            completeTransit(pawn.session, pawn, active, result);
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("Failed to finish the transit of pawn {}", pawn.name, e);
        }
    }

    private void completeTransit(GameSession session, Pawn pawn, ActiveTransit active, ChallengeResult result) {
        stopMusic(active);
        pawn.transit = null;
        active.challenge = null;
        int moved;
        try {
            moved = resolver.roomsMoved(active.transit, result, config.scoring());
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("Transit scoring failed; using the built-in table", e);
            moved = DefaultTransitScoring.roomsMoved(active.transit, result, config.scoring());
        }
        int floor = config.board().breakCheckpoint() ? pawn.checkpoint : 0;
        int destination = MovementRules.afterTransit(active.fromRoom, moved, session.board().size(), floor);
        Component outcome = GameText.transitOutcome(active.transit, result, destination - active.fromRoom);
        for (Player player : pawn.onlineMembers()) {
            player.sendMessage(GameText.info(outcome));
        }
        for (UUID leaving : List.copyOf(pawn.pendingLeaves)) {
            removeMember(session, pawn, leaving);
            if (pawn.removed || session.state != SessionState.RUNNING) {
                return;
            }
        }
        arrive(session, pawn, destination, outcome, true);
    }

    // ------------------------------------------------------------------ challenge music

    private static final long PIGSTEP_TICKS = 148 * 20L;
    private static final long OTHERSIDE_TICKS = 195 * 20L;

    /** An up-tempo disc for the length of the challenge: Pigstep for waves, Otherside for mazes. */
    private void startMusic(ActiveTransit active) {
        boolean waves = active.type == ChallengeType.WAVES;
        active.music = waves ? Sound.MUSIC_DISC_PIGSTEP : Sound.MUSIC_DISC_OTHERSIDE;
        active.musicLengthTicks = waves ? PIGSTEP_TICKS : OTHERSIDE_TICKS;
        playMusic(active, now());
    }

    private void playMusic(ActiveTransit active, long now) {
        Sound music = active.music;
        if (music == null) {
            return;
        }
        active.musicStartedAt = now;
        for (UUID id : active.participants) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                // Emitted from the player, so it follows them down the room instead of fading out.
                player.playSound(player, music, SoundCategory.RECORDS, 1f, 1f);
            }
        }
    }

    private void replayMusic(@Nullable ActiveTransit active, long now) {
        if (active != null && active.music != null && now - active.musicStartedAt >= active.musicLengthTicks) {
            playMusic(active, now);
        }
    }

    private void stopMusic(ActiveTransit active) {
        Sound music = active.music;
        if (music == null) {
            return;
        }
        active.music = null;
        for (UUID id : active.participants) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.stopSound(music, SoundCategory.RECORDS);
            }
        }
    }

    private void abortQuietly(Challenge challenge) {
        try {
            challenge.abort();
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("Challenge abort failed", e);
        }
    }

    // ------------------------------------------------------------------ finishing and ending

    private void finishPawn(GameSession session, Pawn pawn) {
        Board board = session.board();
        pawn.finished = true;
        pawn.roomIndex = board.size();
        pawn.placement = ++session.finishers;
        pawn.finishTicks = now() - session.startTick;
        try {
            services.stats().record(new RunRecord(List.copyOf(pawn.members.keySet()), List.copyOf(pawn.members.values()),
                    board.size(), session.seed, pawn.plies, pawn.finishTicks, Instant.now()));
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("Could not record the run of {}", pawn.name, e);
        }
        broadcast(session, GameText.info(text(pawn.name + " finished in " + pawn.plies + " plies, "
                + MovementRules.clock(pawn.finishTicks) + GameText.placementSuffix(pawn.placement),
                pawn.placement == 1 ? NamedTextColor.GOLD : NamedTextColor.GREEN)));
        Location platform = FinishPlatform.spawn(finishOrigin(session));
        Title title = GameText.finishTitle(pawn.placement);
        for (Player player : pawn.onlineMembers()) {
            resetForGame(player, true);
            player.teleport(platform);
            player.showTitle(title);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        FinishPlatform.celebrate(finishOrigin(session));
        checkEnd(session);
    }

    private void checkEnd(GameSession session) {
        if (session.state != SessionState.RUNNING || session.hasActivePawns()) {
            return;
        }
        boolean anyoneOnline = session.members().stream().anyMatch(id -> Bukkit.getPlayer(id) != null);
        if (anyoneOnline) {
            broadcast(session, GameText.info("Game over! Returning everyone in " + END_DELAY_TICKS / 20 + " seconds."));
        }
        endSession(session, !anyoneOnline, false);
    }

    /**
     * Ends a session. Immediate restores players now; otherwise after a short celebration.
     * syncClear removes all placed blocks before returning (for shutdown).
     */
    private void endSession(GameSession session, boolean immediate, boolean syncClear) {
        switch (session.state) {
            case LOBBY -> {
                for (UUID id : session.members()) {
                    byPlayer.remove(id, session);
                }
                sessions.remove(session);
                session.state = SessionState.CLOSED;
            }
            case STARTING -> {
                for (UUID id : session.members()) {
                    byPlayer.remove(id, session);
                }
                session.state = SessionState.ENDING;
                session.closedOut = true;
                clearBoard(session, syncClear);
            }
            case RUNNING -> {
                session.state = SessionState.ENDING;
                for (Pawn pawn : session.pawns) {
                    dismantle(pawn);
                }
                if (immediate) {
                    closeOut(session, syncClear);
                } else {
                    session.endTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        session.endTask = null;
                        closeOut(session, false);
                    }, END_DELAY_TICKS);
                }
            }
            case ENDING -> {
                if (!immediate) {
                    return;
                }
                BukkitTask pending = session.endTask;
                session.endTask = null;
                if (pending != null) {
                    pending.cancel();
                }
                if (!session.closedOut) {
                    closeOut(session, syncClear);
                } else if (syncClear) {
                    clearBoard(session, true);
                }
            }
            case CLOSED -> {
            }
        }
    }

    /** Restores and releases every remaining member, then clears the world. */
    private void closeOut(GameSession session, boolean syncClear) {
        if (session.closedOut) {
            return;
        }
        session.closedOut = true;
        for (UUID id : session.members()) {
            boolean mine = byPlayer.remove(id, session);
            Player player = Bukkit.getPlayer(id);
            if (mine && player != null) {
                snapshots.restore(player);
            }
        }
        clearBoard(session, syncClear);
    }

    private void clearBoard(GameSession session, boolean sync) {
        BoardBuilder builder = session.builder;
        Runnable done = () -> {
            sessions.remove(session);
            session.state = SessionState.CLOSED;
        };
        if (builder == null) {
            done.run();
        } else {
            builder.clear(sync, done);
        }
    }

    /** Removes a pawn's button and aborts its challenge. */
    private void dismantle(Pawn pawn) {
        removeButton(pawn);
        BukkitTask animation = pawn.animation;
        pawn.animation = null;
        if (animation != null) {
            animation.cancel();
        }
        ActiveTransit active = pawn.transit;
        pawn.transit = null;
        if (active != null) {
            stopMusic(active);
            Challenge challenge = active.challenge;
            active.challenge = null;
            if (challenge != null) {
                abortQuietly(challenge);
            }
        }
    }

    private void removePawn(GameSession session, Pawn pawn, String reason) {
        if (pawn.removed) {
            return;
        }
        pawn.removed = true;
        // Abort before restoring so the challenge cannot touch restored inventories.
        dismantle(pawn);
        for (UUID id : List.copyOf(pawn.members.keySet())) {
            boolean mine = byPlayer.remove(id, session);
            Player player = Bukkit.getPlayer(id);
            if (mine && player != null) {
                snapshots.restore(player);
                player.sendMessage(GameText.info("You left the game."));
            }
        }
        broadcast(session, GameText.info(pawn.name + " " + reason));
        transferHost(session);
        checkEnd(session);
    }

    private void removeMember(GameSession session, Pawn pawn, UUID id) {
        if (pawn.members.size() <= 1) {
            removePawn(session, pawn, "left the game.");
            return;
        }
        pawn.members.remove(id);
        pawn.pendingLeaves.remove(id);
        ActiveTransit active = pawn.transit;
        if (active != null) {
            Player leaving = Bukkit.getPlayer(id);
            if (leaving != null && active.music != null && active.participants.contains(id)) {
                leaving.stopSound(active.music, SoundCategory.RECORDS);
            }
            active.participants.remove(id);
        }
        boolean mine = byPlayer.remove(id, session);
        Player player = Bukkit.getPlayer(id);
        if (mine && player != null) {
            ExitButton button = pawn.button;
            if (button != null) {
                button.hideFrom(plugin, player);
            }
            snapshots.restore(player);
        }
        broadcast(session, GameText.info(session.nameOf(id) + " left the game."));
        transferHost(session);
    }

    // ------------------------------------------------------------------ player state

    private void enterGame(Player player) {
        snapshots.captureIfAbsent(player);
        resetForGame(player, true);
    }

    private static void resetForGame(Player player, boolean heal) {
        if (player.isDead()) {
            return;
        }
        player.closeInventory();
        player.setGameMode(GameMode.ADVENTURE);
        player.getInventory().clear();
        player.setItemOnCursor(null);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.setFlying(false);
        player.setAllowFlight(false);
        if (heal) {
            heal(player);
        }
    }

    private static void heal(Player player) {
        if (player.isDead()) {
            return;
        }
        player.setHealth(PlayerSnapshot.maxHealth(player));
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setExhaustion(0f);
        player.setFireTicks(0);
    }

    private void restorePending(Player player) {
        if (snapshots.has(player.getUniqueId()) && snapshots.restore(player)) {
            player.sendMessage(GameText.info("Your game ended while you were away; your things are back."));
        }
    }

    // ------------------------------------------------------------------ events (called by GameListener)

    void onJoin(Player player) {
        UUID id = player.getUniqueId();
        GameSession session = byPlayer.get(id);
        if (session == null) {
            restorePending(player);
            return;
        }
        switch (session.state) {
            case RUNNING -> rejoin(session, player);
            case ENDING, CLOSED -> {
                byPlayer.remove(id, session);
                restorePending(player);
            }
            case LOBBY, STARTING -> {
            }
        }
    }

    private void rejoin(GameSession session, Player player) {
        UUID id = player.getUniqueId();
        Pawn pawn = session.pawnOf(id);
        if (pawn == null) {
            byPlayer.remove(id, session);
            restorePending(player);
            return;
        }
        enterGame(player);
        pawn.offlineSince = -1;
        if (pawn.finished) {
            player.teleport(FinishPlatform.spawn(finishOrigin(session)));
            player.sendMessage(GameText.info("Welcome back! Your team has finished; the game ends when everyone is done."));
            return;
        }
        ActiveTransit active = pawn.transit;
        if (active != null) {
            // The challenge was handed the participants at its start; a returning player waits it out.
            active.participants.remove(id);
        }
        player.teleport(roomEntrance(session, pawn.roomIndex));
        ExitButton button = pawn.button;
        if (button != null) {
            button.showTo(plugin, player);
        }
        player.sendMessage(GameText.info(active != null
                ? "Welcome back! Your team is in a challenge; you will rejoin them when it ends."
                : "Welcome back! You are in room " + (pawn.roomIndex + 1) + "."));
    }

    void onQuit(Player player) {
        GameSession session = byPlayer.get(player.getUniqueId());
        if (session != null && session.state == SessionState.LOBBY) {
            leave(player);
        }
        // Running games keep the pawn; the rejoin grace period is checked by the ticker.
    }

    /** In a game and protected from damage and hunger (everyone except wave-challenge fighters). */
    boolean isShielded(Player player) {
        UUID id = player.getUniqueId();
        Pawn pawn = playingPawn(id);
        if (pawn == null) {
            return false;
        }
        ActiveTransit active = pawn.transit;
        return active == null || active.type != ChallengeType.WAVES || !active.participants.contains(id);
    }

    /** In a game and outside any challenge: no drops, pickups, block or container use. */
    boolean isRestricted(Player player) {
        Pawn pawn = playingPawn(player.getUniqueId());
        return pawn != null && !pawn.isParticipant(player.getUniqueId());
    }

    /** Where a restricted player belongs right now: their room entrance or the finish platform. */
    @Nullable Location homeOf(Player player) {
        Pawn pawn = playingPawn(player.getUniqueId());
        if (pawn == null || pawn.isParticipant(player.getUniqueId())) {
            return null;
        }
        return pawn.finished ? FinishPlatform.spawn(finishOrigin(pawn.session)) : roomEntrance(pawn.session, pawn.roomIndex);
    }

    /** Sends a player who fell out of their room back to its entrance. */
    void rescue(Player player) {
        Location home = homeOf(player);
        if (home != null) {
            player.setFallDistance(0);
            player.teleport(home);
        }
    }

    private @Nullable Pawn playingPawn(UUID id) {
        GameSession session = byPlayer.get(id);
        if (session == null || session.closedOut
                || (session.state != SessionState.RUNNING && session.state != SessionState.ENDING)) {
            return null;
        }
        return session.pawnOf(id);
    }

    // ------------------------------------------------------------------ ticker

    private void tick() {
        tickCounter += TICK_PERIOD;
        boolean hudTick = tickCounter % 10 == 0;
        long now = now();
        for (GameSession session : List.copyOf(sessions)) {
            try {
                if (session.state == SessionState.RUNNING) {
                    checkGrace(session, now);
                }
                if ((session.state == SessionState.RUNNING || session.state == SessionState.ENDING) && !session.closedOut) {
                    for (Pawn pawn : session.pawns) {
                        if (pawn.removed) {
                            continue;
                        }
                        rescueFallen(session, pawn);
                        replayMusic(pawn.transit, now);
                        if (hudTick) {
                            GameHud.show(session, pawn, now);
                        }
                    }
                }
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().error("Game tick failed", e);
            }
        }
    }

    private void checkGrace(GameSession session, long now) {
        long graceTicks = config.session().rejoinGraceSeconds() * 20L;
        for (Pawn pawn : List.copyOf(session.pawns)) {
            if (!pawn.active()) {
                continue;
            }
            boolean anyoneOnline = pawn.members.keySet().stream().anyMatch(id -> Bukkit.getPlayer(id) != null);
            if (anyoneOnline) {
                pawn.offlineSince = -1;
            } else if (pawn.offlineSince < 0) {
                pawn.offlineSince = now;
            } else if (now - pawn.offlineSince >= graceTicks) {
                removePawn(session, pawn, "was removed: nobody came back in time.");
                if (session.state != SessionState.RUNNING) {
                    return;
                }
            }
        }
    }

    private void rescueFallen(GameSession session, Pawn pawn) {
        World world = services.boardWorld().world();
        Location floor = pawn.finished ? finishOrigin(session) : roomOrigin(session, pawn.roomIndex);
        for (Player player : pawn.onlineMembers()) {
            if (pawn.isParticipant(player.getUniqueId()) || !player.getWorld().equals(world)) {
                continue;
            }
            if (player.getY() < floor.getY() - 2) {
                rescue(player);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private Location roomOrigin(GameSession session, int index) {
        return services.boardWorld().roomOrigin(session.slot, index).clone();
    }

    private Location finishOrigin(GameSession session) {
        return roomOrigin(session, session.board().size());
    }

    private Location roomEntrance(GameSession session, int index) {
        return session.roomTemplates.get(index).entrance().at(roomOrigin(session, index));
    }

    private void broadcast(GameSession session, Component message) {
        forEachOnline(session.members(), player -> player.sendMessage(message));
    }

    private static void forEachOnline(Set<UUID> ids, java.util.function.Consumer<Player> action) {
        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                action.accept(player);
            }
        }
    }

    private static void playRoomSound(Player player, RoomKind kind) {
        switch (kind) {
            case NORMAL -> player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.2f);
            case BREAK -> player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            case LADDER -> player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.4f);
            case CHUTE -> player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
        }
    }

    private static long now() {
        return Bukkit.getCurrentTick();
    }
}
