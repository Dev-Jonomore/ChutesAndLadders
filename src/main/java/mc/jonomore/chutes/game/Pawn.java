package mc.jonomore.chutes.game;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** One team on the board. All members are always in the same room. */
final class Pawn {

    final GameSession session;
    /** 0..n-1 within the session, used for the pawn's arena region. */
    final int slot;
    final String name;
    /** Member UUID to name, in join order. */
    final Map<UUID, String> members = new LinkedHashMap<>();

    int roomIndex;
    int checkpoint;
    int plies;

    boolean finished;
    boolean removed;
    int placement;
    long finishTicks;

    @Nullable ExitButton button;
    @Nullable ActiveTransit transit;
    int transitCounter;
    /** Members who asked to leave while inside a challenge; processed when it ends. */
    final Set<UUID> pendingLeaves = new HashSet<>();

    /** Server tick at which the last member went offline, or -1 while someone is online. */
    long offlineSince = -1;
    /** Far in the past, without risking overflow in {@code now - lastPressTick}. */
    long lastPressTick = Integer.MIN_VALUE;

    Pawn(GameSession session, int slot, String name) {
        this.session = session;
        this.slot = slot;
        this.name = name;
    }

    /** Still on the board: neither finished nor removed. */
    boolean active() {
        return !finished && !removed;
    }

    boolean isMember(UUID player) {
        return members.containsKey(player);
    }

    boolean isParticipant(UUID player) {
        ActiveTransit current = transit;
        return current != null && current.participants.contains(player);
    }

    List<Player> onlineMembers() {
        List<Player> online = new ArrayList<>(members.size());
        for (UUID id : members.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                online.add(player);
            }
        }
        return online;
    }
}
