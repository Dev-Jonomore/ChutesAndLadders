package mc.jonomore.chutes.stats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Keeps every finished run in memory and persists them as a list under {@code runs} in a YAML
 * file. Each save writes a temp file next to the target and moves it over, so a crash mid-write
 * never leaves a truncated stats file.
 */
public final class YamlStatsStore implements StatsStore {

    private static final Logger LOGGER = Logger.getLogger(YamlStatsStore.class.getName());
    private static final String RUNS = "runs";

    private static final Comparator<RunRecord> BY_PLIES = Comparator.comparingInt(RunRecord::plies)
            .thenComparingLong(RunRecord::ticks)
            .thenComparing(RunRecord::finishedAt);
    private static final Comparator<RunRecord> BY_TIME = Comparator.comparingLong(RunRecord::ticks)
            .thenComparingInt(RunRecord::plies)
            .thenComparing(RunRecord::finishedAt);

    private final Path file;
    private final List<RunRecord> runs = new ArrayList<>();

    /**
     * Loads the runs already in {@code file}, if it exists. An unreadable file is moved aside to
     * {@code <name>.corrupt-<millis>} rather than being overwritten by the next save.
     */
    public YamlStatsStore(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            load();
        }
    }

    @Override
    public synchronized void record(RunRecord run) {
        runs.add(run);
        try {
            save();
        } catch (IOException e) {
            // The run stays in memory and is written with the next successful save.
            LOGGER.log(Level.WARNING, "Could not save stats to " + file, e);
        }
    }

    @Override
    public synchronized List<RunRecord> top(int rooms, int teamSize, Metric metric, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return runs.stream()
                .filter(run -> run.rooms() == rooms && run.teamSize() == teamSize)
                .sorted(metric == Metric.PLIES ? BY_PLIES : BY_TIME)
                .limit(limit)
                .toList();
    }

    private void load() {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read stats from " + file, e);
        } catch (InvalidConfigurationException e) {
            quarantine(e);
            return;
        }
        for (Map<?, ?> map : yaml.getMapList(RUNS)) {
            try {
                runs.add(fromMap(map));
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Skipping malformed run in " + file + ": " + map, e);
            }
        }
    }

    private void quarantine(Exception cause) {
        Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        LOGGER.log(Level.SEVERE, "Stats file " + file + " is not valid YAML; moving it to " + aside, cause);
        try {
            Files.move(file, aside);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not move corrupt stats file " + file + " aside", e);
        }
    }

    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(RUNS, runs.stream().map(YamlStatsStore::toMap).toList());
        String content = yaml.saveToString();

        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Map<String, Object> toMap(RunRecord run) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("members", run.members().stream().map(UUID::toString).toList());
        map.put("names", run.names());
        map.put("rooms", run.rooms());
        map.put("seed", run.seed());
        map.put("plies", run.plies());
        map.put("ticks", run.ticks());
        map.put("finished-at", run.finishedAt().toString());
        return map;
    }

    private static RunRecord fromMap(Map<?, ?> map) {
        List<UUID> members = ((List<?>) require(map, "members")).stream()
                .map(value -> UUID.fromString(value.toString()))
                .toList();
        List<String> names = ((List<?>) require(map, "names")).stream()
                .map(Object::toString)
                .toList();
        return new RunRecord(
                members,
                names,
                ((Number) require(map, "rooms")).intValue(),
                // SnakeYAML reads small longs back as Integer, so go through Number.
                ((Number) require(map, "seed")).longValue(),
                ((Number) require(map, "plies")).intValue(),
                ((Number) require(map, "ticks")).longValue(),
                Instant.parse(require(map, "finished-at").toString()));
    }

    private static Object require(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }
}
