package mc.jonomore.chutes;

import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

/** Typed, validated view of config.yml. */
public record ChutesConfig(
        String world,
        BoardSettings board,
        Scoring scoring,
        Maze maze,
        Waves waves,
        Layout layout,
        Session session) {

    public record BoardSettings(
            int defaultRooms,
            int maxRoll,
            int breakInterval,
            boolean breakCheckpoint,
            int safeStart,
            double ladderDensity,
            double chuteDensity,
            int minLength,
            int maxLength,
            double mazeWeight) {
    }

    public record Scoring(int ladderMinGain, int chuteMinRegress) {
    }

    public record Maze(int roomsPerFloor, int minFloors, int maxFloors, int cellsWide, int cellsLong,
            int secondsPerFloor) {
    }

    public record Waves(int mobsPerRoom, int waveSize, int secondsPerMob, List<EntityType> mobTypes) {

        public Waves {
            mobTypes = List.copyOf(mobTypes);
        }
    }

    public record Layout(int roomSpacing, int sessionSpacing, int baseY) {
    }

    public record Session(int maxTeamSize, int rejoinGraceSeconds) {
    }

    public static ChutesConfig load(FileConfiguration config) {
        ConfigurationSection board = section(config, "board");
        ConfigurationSection scoring = section(config, "scoring");
        ConfigurationSection maze = section(config, "maze");
        ConfigurationSection waves = section(config, "waves");
        ConfigurationSection layout = section(config, "layout");
        ConfigurationSection session = section(config, "session");

        int minLength = atLeast(board, "min-length", 1);
        int minFloors = atLeast(maze, "min-floors", 1);

        List<EntityType> mobTypes = waves.getStringList("mob-types").stream()
                .map(ChutesConfig::mobType)
                .toList();
        if (mobTypes.isEmpty()) {
            throw new IllegalArgumentException("waves.mob-types must list at least one mob");
        }

        return new ChutesConfig(
                config.getString("world", "chutes_board"),
                new BoardSettings(
                        atLeast(board, "default-rooms", 5),
                        atLeast(board, "max-roll", 1),
                        atLeast(board, "break-interval", 2),
                        board.getBoolean("break-checkpoint"),
                        atLeast(board, "safe-start", 1),
                        fraction(board, "ladder-density"),
                        fraction(board, "chute-density"),
                        minLength,
                        Math.max(minLength, board.getInt("max-length")),
                        fraction(board, "maze-weight")),
                new Scoring(
                        atLeast(scoring, "ladder-min-gain", 0),
                        atLeast(scoring, "chute-min-regress", 0)),
                new Maze(
                        atLeast(maze, "rooms-per-floor", 1),
                        minFloors,
                        Math.max(minFloors, maze.getInt("max-floors")),
                        atLeast(maze, "cells-wide", 2),
                        atLeast(maze, "cells-long", 2),
                        atLeast(maze, "seconds-per-floor", 1)),
                new Waves(
                        atLeast(waves, "mobs-per-room", 1),
                        atLeast(waves, "wave-size", 1),
                        atLeast(waves, "seconds-per-mob", 1),
                        mobTypes),
                new Layout(
                        atLeast(layout, "room-spacing", 16),
                        atLeast(layout, "session-spacing", 256),
                        layout.getInt("base-y")),
                new Session(
                        atLeast(session, "max-team-size", 1),
                        atLeast(session, "rejoin-grace-seconds", 0)));
    }

    private static ConfigurationSection section(FileConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalArgumentException("config.yml is missing the " + path + " section");
        }
        return section;
    }

    private static int atLeast(ConfigurationSection section, String key, int min) {
        int value = section.getInt(key);
        if (value < min) {
            throw new IllegalArgumentException(
                    section.getCurrentPath() + "." + key + " must be at least " + min + ", got " + value);
        }
        return value;
    }

    private static double fraction(ConfigurationSection section, String key) {
        double value = section.getDouble(key);
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException(
                    section.getCurrentPath() + "." + key + " must be between 0 and 1, got " + value);
        }
        return value;
    }

    private static EntityType mobType(String name) {
        try {
            return EntityType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("waves.mob-types: unknown entity type " + name);
        }
    }
}
