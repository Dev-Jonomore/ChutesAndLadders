package mc.jonomore.chutes.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mc.jonomore.chutes.board.Board;
import mc.jonomore.chutes.world.RoomTemplate;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/** One game, from lobby to cleanup. */
final class GameSession {

    final int rooms;
    final long seed;
    UUID host;
    SessionState state = SessionState.LOBBY;
    /** Session slot in the board world, -1 until started. Held until the board is cleared. */
    int slot = -1;

    /** Lobby teams: name to members. Frozen into {@link #pawns} when the game begins. */
    final Map<String, List<UUID>> teams = new LinkedHashMap<>();
    /** Every player ever in the session, to their name. */
    final Map<UUID, String> names = new HashMap<>();

    @Nullable Board board;
    /** Template for each room index, chosen from the board seed. */
    List<RoomTemplate> roomTemplates = List.of();
    @Nullable BoardBuilder builder;
    final List<Pawn> pawns = new ArrayList<>();

    long startTick;
    int finishers;
    @Nullable BukkitTask endTask;
    /** Players have been restored and released; only world cleanup remains. */
    boolean closedOut;

    GameSession(UUID host, String hostName, int rooms, long seed) {
        this.host = host;
        this.rooms = rooms;
        this.seed = seed;
        names.put(host, hostName);
        addToTeam(host, hostName);
    }

    Board board() {
        Board current = board;
        if (current == null) {
            throw new IllegalStateException("board not generated");
        }
        return current;
    }

    String nameOf(UUID player) {
        return names.getOrDefault(player, player.toString());
    }

    @Nullable String teamOf(UUID player) {
        for (Map.Entry<String, List<UUID>> team : teams.entrySet()) {
            if (team.getValue().contains(player)) {
                return team.getKey();
            }
        }
        return null;
    }

    void addToTeam(UUID player, String team) {
        teams.computeIfAbsent(team, ignored -> new ArrayList<>()).add(player);
    }

    void removeFromTeams(UUID player) {
        teams.values().forEach(members -> members.remove(player));
        teams.values().removeIf(List::isEmpty);
    }

    @Nullable Pawn pawnOf(UUID player) {
        for (Pawn pawn : pawns) {
            if (!pawn.removed && pawn.isMember(player)) {
                return pawn;
            }
        }
        return null;
    }

    /** Everyone currently belonging to the session (lobby teams before the start, pawn members after). */
    Set<UUID> members() {
        Set<UUID> all = new LinkedHashSet<>();
        if (state == SessionState.LOBBY || state == SessionState.STARTING) {
            teams.values().forEach(all::addAll);
        } else {
            for (Pawn pawn : pawns) {
                if (!pawn.removed) {
                    all.addAll(pawn.members.keySet());
                }
            }
        }
        return all;
    }

    boolean hasActivePawns() {
        return pawns.stream().anyMatch(Pawn::active);
    }
}
