package mc.jonomore.chutes.world;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;

/**
 * Templates saved under {@code <data folder>/templates/<type>/<id>.nbt} with a {@code <id>.yml}
 * sidecar for the markers, falling back to {@link BuiltinTemplates} for types with nothing saved.
 * Main thread only.
 */
public final class FileTemplateStore implements TemplateStore {

    /** Footprint of an arena region, see {@link BoardWorld#arenaOrigin(int, int)}. */
    public static final int ARENA_FOOTPRINT = 128;

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final String NBT = ".nbt";
    private static final String YML = ".yml";

    /** Outcome of a successful save; warnings are worth showing but did not block the save. */
    public record SaveResult(RoomTemplate template, boolean replaced, List<String> warnings) {

        public SaveResult {
            warnings = List.copyOf(warnings);
        }
    }

    private final Plugin plugin;
    private final Path root;
    private final Map<TemplateType, TreeMap<String, RoomTemplate>> saved = new EnumMap<>(TemplateType.class);
    private int roomFootprint;

    public FileTemplateStore(Plugin plugin) {
        this.plugin = plugin;
        this.root = plugin.getDataFolder().toPath().resolve("templates");
        reload();
    }

    @Override
    public List<RoomTemplate> all(TemplateType type) {
        List<RoomTemplate> own = savedTemplates(type);
        return own.isEmpty() ? builtinTemplates(type) : own;
    }

    @Override
    public Optional<RoomTemplate> get(TemplateType type, String id) {
        RoomTemplate own = saved.get(type).get(id);
        if (own != null) {
            return Optional.of(own);
        }
        return BuiltinTemplates.of(type).stream().filter(t -> t.id().equals(id)).findFirst();
    }

    @Override
    public void reload() {
        roomFootprint = plugin.getConfig().getInt("layout.room-spacing", 64);
        for (TemplateType type : TemplateType.values()) {
            TreeMap<String, RoomTemplate> templates = new TreeMap<>();
            saved.put(type, templates);
            Path dir = directory(type);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + YML)) {
                for (Path sidecar : files) {
                    String file = sidecar.getFileName().toString();
                    String id = file.substring(0, file.length() - YML.length());
                    try {
                        templates.put(id, load(type, id));
                    } catch (TemplateException | IOException | InvalidConfigurationException | RuntimeException e) {
                        plugin.getSLF4JLogger().warn("Skipping template {}/{}: {}", type, id, e.getMessage());
                    }
                }
            } catch (IOException e) {
                plugin.getSLF4JLogger().warn("Could not list templates in {}: {}", dir, e.getMessage());
            }
        }
        // Built-ins are generated once; touching them here keeps the first game start fast.
        for (TemplateType type : TemplateType.values()) {
            builtinTemplates(type);
        }
    }

    /** Only the templates saved on disk for a type, sorted by id; may be empty. */
    public List<RoomTemplate> savedTemplates(TemplateType type) {
        return List.copyOf(saved.get(type).values());
    }

    /** The built-in fallbacks for a type that fit its footprint, sorted by id. */
    public List<RoomTemplate> builtinTemplates(TemplateType type) {
        int footprint = footprint(type);
        List<RoomTemplate> fitting = BuiltinTemplates.of(type).stream()
                .filter(t -> t.size().getBlockX() <= footprint && t.size().getBlockZ() <= footprint)
                .toList();
        if (fitting.isEmpty() && (type == TemplateType.LADDER || type == TemplateType.CHUTE)) {
            // The long transit rooms need more room than the minimum spacing; plain rooms still work.
            return builtinTemplates(TemplateType.ROOM);
        }
        if (fitting.isEmpty()) {
            throw new IllegalStateException("no built-in " + type + " template fits a " + footprint + " footprint");
        }
        return fitting;
    }

    /** Largest x and z extent allowed for a type: layout.room-spacing for rooms, 128 for arenas. */
    public int footprint(TemplateType type) {
        return type == TemplateType.ARENA ? ARENA_FOOTPRINT : roomFootprint;
    }

    public static boolean isValidId(String id) {
        return ID.matcher(id).matches() && !id.startsWith(BuiltinTemplates.PREFIX);
    }

    /**
     * Saves the blocks (and non-player entities) of a world region as a template, replacing any
     * template of the same type and id. Marker offsets are relative to {@code min}.
     *
     * @throws TemplateException with an author-facing message when the template is invalid or cannot be written
     */
    public SaveResult save(TemplateType type, String id, Location min, BlockVector size, SpawnPoint entrance,
            List<ButtonSpot> buttons, List<Vector> spawns) throws TemplateException {
        if (!isValidId(id)) {
            throw new TemplateException("Template id must be 1-32 characters of a-z, 0-9, _ or -, and must not start with "
                    + BuiltinTemplates.PREFIX);
        }
        List<String> problems = TemplateValidation.problems(type, size, footprint(type), entrance, buttons, spawns);
        if (!problems.isEmpty()) {
            throw new TemplateException(String.join("; ", problems));
        }
        Location base = Regions.blockOrigin(min);
        List<String> warnings = checkInWorld(base, buttons);

        Regions.loadChunks(base, size);
        StructureManager manager = Bukkit.getStructureManager();
        Structure structure = manager.createStructure();
        structure.fill(base, size, true);

        boolean replaced = saved.get(type).containsKey(id);
        try {
            Files.createDirectories(directory(type));
            manager.saveStructure(nbtFile(type, id), structure);
            writeSidecar(type, id, size, entrance, buttons, spawns);
        } catch (IOException e) {
            throw new TemplateException("Could not write template files: " + e.getMessage());
        }
        RoomTemplate template = new SavedTemplate(id, type, structure, entrance, buttons, spawns);
        saved.get(type).put(id, template);
        return new SaveResult(template, replaced, warnings);
    }

    /**
     * Deletes a saved template and its files.
     *
     * @return false when no saved template of that type and id exists
     * @throws TemplateException when the files cannot be removed
     */
    public boolean delete(TemplateType type, String id) throws TemplateException {
        File nbt = nbtFile(type, id);
        File yml = ymlFile(type, id);
        boolean known = saved.get(type).remove(id) != null;
        try {
            boolean removed = Files.deleteIfExists(nbt.toPath());
            removed |= Files.deleteIfExists(yml.toPath());
            return known || removed;
        } catch (IOException e) {
            throw new TemplateException("Could not delete template files: " + e.getMessage());
        }
    }

    private List<String> checkInWorld(Location base, List<ButtonSpot> buttons) {
        World world = base.getWorld();
        List<String> warnings = new ArrayList<>();
        for (ButtonSpot spot : buttons) {
            Location at = base.clone().add(spot.block());
            Block block = world.getBlockAt(at);
            Block front = block.getRelative(spot.face());
            if (!block.getType().isSolid()) {
                warnings.add("button spot " + TemplateValidation.format(spot.block()) + " is attached to "
                        + block.getType().name().toLowerCase(Locale.ROOT) + ", which is not solid");
            }
            if (!front.getType().isAir()) {
                warnings.add("button spot " + TemplateValidation.format(spot.block()) + " " + spot.face()
                        + " has " + front.getType().name().toLowerCase(Locale.ROOT) + " in front of it instead of air");
            }
        }
        return warnings;
    }

    private RoomTemplate load(TemplateType type, String id)
            throws TemplateException, IOException, InvalidConfigurationException {
        if (!ID.matcher(id).matches()) {
            throw new TemplateException("invalid id");
        }
        File nbt = nbtFile(type, id);
        if (!nbt.isFile()) {
            throw new TemplateException("missing " + nbt.getName());
        }
        YamlConfiguration yml = new YamlConfiguration();
        yml.load(ymlFile(type, id));

        Structure structure = Bukkit.getStructureManager().loadStructure(nbt);
        BlockVector size = structure.getSize();
        ConfigurationSection sizeSection = yml.getConfigurationSection("size");
        if (sizeSection != null && !readBlockVector(sizeSection).equals(size)) {
            plugin.getSLF4JLogger().warn("Template {}/{}: sidecar size differs from the structure, using {}",
                    type, id, TemplateValidation.format(size));
        }

        ConfigurationSection entranceSection = yml.getConfigurationSection("entrance");
        SpawnPoint entrance = entranceSection == null ? null : new SpawnPoint(
                new Vector(entranceSection.getDouble("x"), entranceSection.getDouble("y"), entranceSection.getDouble("z")),
                (float) entranceSection.getDouble("yaw"), (float) entranceSection.getDouble("pitch"));

        List<ButtonSpot> buttons = new ArrayList<>();
        for (String line : yml.getStringList("buttons")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 4) {
                throw new TemplateException("bad button line '" + line + "', expected 'x y z FACE'");
            }
            BlockFace face = BlockFace.valueOf(parts[3].toUpperCase(Locale.ROOT));
            if (!face.isCartesian()) {
                throw new TemplateException("button face must be one of the six block faces: " + line);
            }
            buttons.add(new ButtonSpot(new BlockVector(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])), face));
        }
        List<Vector> spawns = new ArrayList<>();
        for (String line : yml.getStringList("spawns")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 3) {
                throw new TemplateException("bad spawn line '" + line + "', expected 'x y z'");
            }
            spawns.add(new Vector(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2])));
        }

        List<String> problems = TemplateValidation.problems(type, size, footprint(type), entrance, buttons, spawns);
        if (!problems.isEmpty()) {
            throw new TemplateException(String.join("; ", problems));
        }
        return new SavedTemplate(id, type, structure, entrance, buttons, spawns);
    }

    private void writeSidecar(TemplateType type, String id, BlockVector size, SpawnPoint entrance,
            List<ButtonSpot> buttons, List<Vector> spawns) throws IOException {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("type", type.name().toLowerCase(Locale.ROOT));
        yml.set("size.x", size.getBlockX());
        yml.set("size.y", size.getBlockY());
        yml.set("size.z", size.getBlockZ());
        Vector e = entrance.offset();
        yml.set("entrance.x", e.getX());
        yml.set("entrance.y", e.getY());
        yml.set("entrance.z", e.getZ());
        yml.set("entrance.yaw", (double) entrance.yaw());
        yml.set("entrance.pitch", (double) entrance.pitch());
        yml.set("buttons", buttons.stream()
                .map(b -> b.block().getBlockX() + " " + b.block().getBlockY() + " " + b.block().getBlockZ()
                        + " " + b.face().name())
                .toList());
        yml.set("spawns", spawns.stream().map(s -> s.getX() + " " + s.getY() + " " + s.getZ()).toList());
        yml.save(ymlFile(type, id));
    }

    private static BlockVector readBlockVector(ConfigurationSection section) {
        return new BlockVector(section.getInt("x"), section.getInt("y"), section.getInt("z"));
    }

    private Path directory(TemplateType type) {
        return root.resolve(type.name().toLowerCase(Locale.ROOT));
    }

    private File nbtFile(TemplateType type, String id) {
        return directory(type).resolve(id + NBT).toFile();
    }

    private File ymlFile(TemplateType type, String id) {
        return directory(type).resolve(id + YML).toFile();
    }
}
