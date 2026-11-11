package mc.jonomore.chutes.stats;

import java.util.List;

public interface StatsStore {

    enum Metric {
        PLIES,
        TIME
    }

    /** Persist a finished run. */
    void record(RunRecord run);

    /** Best runs for a board size and team size, best first. */
    List<RunRecord> top(int rooms, int teamSize, Metric metric, int limit);
}
