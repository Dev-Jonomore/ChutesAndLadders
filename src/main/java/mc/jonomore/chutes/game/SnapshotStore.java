package mc.jonomore.chutes.game;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Saved player state for everyone who entered a game and has not been restored yet. Each
 * snapshot is also written to {@code player-snapshots/<uuid>.yml} the moment it is taken, so a
 * crash, a restart or a player who is offline when the game ends never costs an inventory.
 */
final class SnapshotStore {

    private final Plugin plugin;
    private final File directory;
    private final Map<UUID, PlayerSnapshot> held = new HashMap<>();

    SnapshotStore(Plugin plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "player-snapshots");
    }

    boolean has(UUID player) {
        return held.containsKey(player) || file(player).isFile();
    }

    /** Takes a snapshot unless one is already held: an existing one is always the original state. */
    void captureIfAbsent(Player player) {
        UUID id = player.getUniqueId();
        if (load(id) != null) {
            return;
        }
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
        held.put(id, snapshot);
        try {
            directory.mkdirs();
            snapshot.toYaml().save(file(id));
        } catch (IOException e) {
            plugin.getSLF4JLogger().warn("Could not persist the saved state of {}", player.getName(), e);
        }
    }

    /** Restores and forgets the player's snapshot. Returns false when there was none. */
    boolean restore(Player player) {
        UUID id = player.getUniqueId();
        PlayerSnapshot snapshot = load(id);
        if (snapshot == null) {
            return false;
        }
        held.remove(id);
        try {
            snapshot.apply(player);
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("Failed to fully restore {}; the saved state is kept on disk", player.getName(), e);
            return true;
        }
        File file = file(id);
        if (file.isFile() && !file.delete()) {
            plugin.getSLF4JLogger().warn("Could not delete {}", file);
        }
        return true;
    }

    private @Nullable PlayerSnapshot load(UUID id) {
        PlayerSnapshot snapshot = held.get(id);
        if (snapshot != null) {
            return snapshot;
        }
        File file = file(id);
        if (!file.isFile()) {
            return null;
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file);
            snapshot = PlayerSnapshot.fromYaml(yaml);
            held.put(id, snapshot);
            return snapshot;
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            // Keep the unreadable file aside for manual recovery instead of overwriting it later.
            File broken = new File(directory, file.getName() + ".broken");
            plugin.getSLF4JLogger().error("Could not read saved player state {}; moved to {}", file, broken, e);
            if (!file.renameTo(broken)) {
                plugin.getSLF4JLogger().warn("Could not move {} aside", file);
            }
            return null;
        }
    }

    private File file(UUID id) {
        return new File(directory, id + ".yml");
    }
}
