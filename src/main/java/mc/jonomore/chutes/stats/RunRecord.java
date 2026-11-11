package mc.jonomore.chutes.stats;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One finished run of one pawn. members and names are parallel lists. */
public record RunRecord(
        List<UUID> members,
        List<String> names,
        int rooms,
        long seed,
        int plies,
        long ticks,
        Instant finishedAt) {

    public RunRecord {
        members = List.copyOf(members);
        names = List.copyOf(names);
    }

    public int teamSize() {
        return members.size();
    }
}
