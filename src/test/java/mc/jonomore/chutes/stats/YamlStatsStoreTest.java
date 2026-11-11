package mc.jonomore.chutes.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import mc.jonomore.chutes.stats.StatsStore.Metric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlStatsStoreTest {

    @TempDir
    Path dir;

    private static RunRecord run(int rooms, int teamSize, int plies, long ticks, long finishedAt) {
        List<UUID> members = Stream.generate(UUID::randomUUID).limit(teamSize).toList();
        List<String> names = members.stream().map(id -> "p" + id.toString().substring(0, 6)).toList();
        return new RunRecord(members, names, rooms, 42L, plies, ticks, Instant.ofEpochMilli(finishedAt));
    }

    @Test
    void startsEmptyWithoutFile() {
        YamlStatsStore store = new YamlStatsStore(dir.resolve("stats.yml"));
        assertEquals(List.of(), store.top(50, 1, Metric.PLIES, 10));
        assertTrue(Files.notExists(dir.resolve("stats.yml")));
    }

    @Test
    void recordedRunsSurviveReload() {
        Path file = dir.resolve("nested").resolve("stats.yml");
        RunRecord solo = new RunRecord(
                List.of(UUID.randomUUID()), List.of("Alice"), 50, Long.MIN_VALUE, 17, 12_345L,
                Instant.parse("2026-11-11T10:15:30.123Z"));
        RunRecord duo = new RunRecord(
                List.of(UUID.randomUUID(), UUID.randomUUID()), List.of("Bob", "Carol"), 50, 7L, 20,
                Long.MAX_VALUE, Instant.parse("2026-11-11T11:00:00Z"));

        YamlStatsStore store = new YamlStatsStore(file);
        store.record(solo);
        store.record(duo);
        assertTrue(Files.exists(file));

        YamlStatsStore reloaded = new YamlStatsStore(file);
        assertEquals(List.of(solo), reloaded.top(50, 1, Metric.PLIES, 10));
        assertEquals(List.of(duo), reloaded.top(50, 2, Metric.TIME, 10));
    }

    @Test
    void leavesNoTempFilesBehind() throws IOException {
        Path file = dir.resolve("stats.yml");
        YamlStatsStore store = new YamlStatsStore(file);
        for (int i = 0; i < 5; i++) {
            store.record(run(50, 1, 10 + i, 100, i));
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(file), files.toList());
        }
    }

    @Test
    void topFiltersByRoomsAndTeamSize() {
        YamlStatsStore store = new YamlStatsStore(dir.resolve("stats.yml"));
        RunRecord match = run(50, 2, 30, 900, 1);
        store.record(run(50, 1, 10, 100, 2));
        store.record(run(30, 2, 10, 100, 3));
        store.record(match);

        assertEquals(List.of(match), store.top(50, 2, Metric.PLIES, 10));
        assertEquals(List.of(), store.top(40, 2, Metric.PLIES, 10));
    }

    @Test
    void topSortsByMetricAndLimits() {
        YamlStatsStore store = new YamlStatsStore(dir.resolve("stats.yml"));
        RunRecord fewPliesSlow = run(50, 1, 10, 9000, 1);
        RunRecord manyPliesFast = run(50, 1, 40, 1000, 2);
        RunRecord middle = run(50, 1, 20, 5000, 3);
        store.record(middle);
        store.record(manyPliesFast);
        store.record(fewPliesSlow);

        assertEquals(List.of(fewPliesSlow, middle, manyPliesFast), store.top(50, 1, Metric.PLIES, 10));
        assertEquals(List.of(manyPliesFast, middle, fewPliesSlow), store.top(50, 1, Metric.TIME, 10));
        assertEquals(List.of(fewPliesSlow, middle), store.top(50, 1, Metric.PLIES, 2));
        assertEquals(List.of(), store.top(50, 1, Metric.PLIES, 0));
    }

    @Test
    void tiesBreakOnOtherMetricThenEarliestFinish() {
        YamlStatsStore store = new YamlStatsStore(dir.resolve("stats.yml"));
        RunRecord later = run(50, 1, 10, 500, 200);
        RunRecord earlier = run(50, 1, 10, 500, 100);
        RunRecord slower = run(50, 1, 10, 800, 50);
        store.record(later);
        store.record(slower);
        store.record(earlier);

        assertEquals(List.of(earlier, later, slower), store.top(50, 1, Metric.PLIES, 10));
    }

    @Test
    void corruptFileIsMovedAsideAndStoreStartsEmpty() throws IOException {
        Path file = dir.resolve("stats.yml");
        Files.writeString(file, "runs: [ {{{ not yaml");

        YamlStatsStore store = new YamlStatsStore(file);
        assertEquals(List.of(), store.top(50, 1, Metric.PLIES, 10));
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("stats.yml.corrupt-")));
        }

        RunRecord fresh = run(50, 1, 10, 100, 1);
        store.record(fresh);
        assertEquals(List.of(fresh), new YamlStatsStore(file).top(50, 1, Metric.PLIES, 10));
    }

    @Test
    void malformedEntriesAreSkipped() throws IOException {
        Path file = dir.resolve("stats.yml");
        UUID id = UUID.randomUUID();
        Files.writeString(file, """
                runs:
                - members: [%s]
                  names: [Alice]
                  rooms: 50
                  seed: 3
                  plies: 12
                  ticks: 400
                  finished-at: '2026-11-11T10:00:00Z'
                - rooms: 50
                - members: [not-a-uuid]
                  names: [Bob]
                  rooms: 50
                  seed: 3
                  plies: 1
                  ticks: 1
                  finished-at: '2026-11-11T10:00:00Z'
                """.formatted(id));

        RunRecord good = new RunRecord(
                List.of(id), List.of("Alice"), 50, 3, 12, 400, Instant.parse("2026-11-11T10:00:00Z"));
        assertEquals(List.of(good), new YamlStatsStore(file).top(50, 1, Metric.PLIES, 10));
    }
}
