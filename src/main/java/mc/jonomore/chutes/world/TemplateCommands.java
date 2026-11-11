package mc.jonomore.chutes.world;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import mc.jonomore.chutes.command.CommandModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BlockVector;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * {@code /chutes template ...}: authoring room, break and arena templates in-game. The selection
 * being built is kept per player in memory; markers are absolute until saved.
 */
public final class TemplateCommands implements CommandModule {

    public static final String PERMISSION = "chutes.admin";
    private static final double BUTTON_RANGE = 8.0;

    private final Plugin plugin;
    private final FileTemplateStore store;
    private final Map<UUID, Selection> selections = new HashMap<>();

    /** In-progress template: corners and markers in absolute world coordinates. */
    private static final class Selection {
        World world;
        BlockVector pos1;
        BlockVector pos2;
        Location entrance;
        final Map<BlockVector, BlockFace> buttons = new LinkedHashMap<>();
        final List<Location> spawns = new ArrayList<>();

        BlockVector min() {
            return new BlockVector(Math.min(pos1.getBlockX(), pos2.getBlockX()),
                    Math.min(pos1.getBlockY(), pos2.getBlockY()), Math.min(pos1.getBlockZ(), pos2.getBlockZ()));
        }

        BlockVector size() {
            return new BlockVector(Math.abs(pos1.getBlockX() - pos2.getBlockX()) + 1,
                    Math.abs(pos1.getBlockY() - pos2.getBlockY()) + 1, Math.abs(pos1.getBlockZ() - pos2.getBlockZ()) + 1);
        }
    }

    /**
     * @param templates must be the {@link FileTemplateStore}, since authoring writes files
     */
    public TemplateCommands(Plugin plugin, TemplateStore templates) {
        if (!(templates instanceof FileTemplateStore fileStore)) {
            throw new IllegalArgumentException("TemplateCommands needs a FileTemplateStore, got "
                    + templates.getClass().getName());
        }
        this.plugin = plugin;
        this.store = fileStore;
    }

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("template")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.literal("pos1").executes(ctx -> withPlayer(ctx, p -> corner(p, true))))
                .then(Commands.literal("pos2").executes(ctx -> withPlayer(ctx, p -> corner(p, false))))
                .then(Commands.literal("entrance").executes(ctx -> withPlayer(ctx, this::entrance)))
                .then(Commands.literal("button").executes(ctx -> withPlayer(ctx, this::button)))
                .then(Commands.literal("spawn").executes(ctx -> withPlayer(ctx, this::spawn)))
                .then(Commands.literal("clear").executes(ctx -> withPlayer(ctx, this::clearSelection)))
                .then(Commands.literal("save")
                        .then(Commands.argument("type", StringArgumentType.word()).suggests(this::suggestTypes)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(idSuggestions(true))
                                        .executes(ctx -> withPlayer(ctx, p -> save(p, ctx))))))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx.getSource().getSender(), null))
                        .then(Commands.argument("type", StringArgumentType.word()).suggests(this::suggestTypes)
                                .executes(ctx -> {
                                    CommandSender sender = ctx.getSource().getSender();
                                    Optional<TemplateType> type = type(sender, ctx);
                                    return type.isPresent() ? list(sender, type.get()) : 0;
                                })))
                .then(Commands.literal("delete")
                        .then(Commands.argument("type", StringArgumentType.word()).suggests(this::suggestTypes)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(idSuggestions(true))
                                        .executes(this::delete))))
                .then(Commands.literal("paste")
                        .then(Commands.argument("type", StringArgumentType.word()).suggests(this::suggestTypes)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(idSuggestions(false))
                                        .executes(ctx -> withPlayer(ctx, p -> paste(p, ctx)))))));
    }

    private int withPlayer(CommandContext<CommandSourceStack> ctx, Function<Player, Integer> action) {
        if (ctx.getSource().getExecutor() instanceof Player player) {
            return action.apply(player);
        }
        error(ctx.getSource().getSender(), "Only a player can do that, since it uses your position.");
        return 0;
    }

    private Selection selection(Player player) {
        return selections.computeIfAbsent(player.getUniqueId(), id -> new Selection());
    }

    /** The player's selection, reset first if it belongs to another world. */
    private Selection selectionIn(Player player) {
        Selection selection = selection(player);
        if (selection.world != null && !selection.world.equals(player.getWorld())) {
            selections.remove(player.getUniqueId());
            info(player, "You changed worlds, so your previous template selection was cleared.");
            selection = selection(player);
        }
        selection.world = player.getWorld();
        return selection;
    }

    private int corner(Player player, boolean first) {
        Selection selection = selectionIn(player);
        Location at = player.getLocation();
        BlockVector pos = new BlockVector(at.getBlockX(), at.getBlockY(), at.getBlockZ());
        if (first) {
            selection.pos1 = pos;
        } else {
            selection.pos2 = pos;
        }
        success(player, "Corner " + (first ? 1 : 2) + " set to " + TemplateValidation.format(pos) + ".");
        status(player, selection);
        return Command.SINGLE_SUCCESS;
    }

    private int entrance(Player player) {
        Selection selection = selectionIn(player);
        selection.entrance = player.getLocation();
        success(player, String.format(Locale.ROOT, "Entrance set to %s facing yaw %.0f, pitch %.0f.",
                TemplateValidation.format(player.getLocation().toVector()), player.getYaw(), player.getPitch()));
        status(player, selection);
        return Command.SINGLE_SUCCESS;
    }

    private int button(Player player) {
        RayTraceResult hit = player.rayTraceBlocks(BUTTON_RANGE, FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) {
            error(player, "Look at the face of a block within " + (int) BUTTON_RANGE + " blocks.");
            return 0;
        }
        Block block = hit.getHitBlock();
        BlockFace face = hit.getHitBlockFace();
        if (!block.getType().isSolid()) {
            error(player, "A button needs a solid block to sit on; that is " + name(block) + ".");
            return 0;
        }
        Block front = block.getRelative(face);
        if (!front.getType().isAir()) {
            error(player, "The space in front of that face holds " + name(front) + "; a button needs air there.");
            return 0;
        }
        Selection selection = selectionIn(player);
        BlockVector pos = new BlockVector(block.getX(), block.getY(), block.getZ());
        String where = TemplateValidation.format(pos) + " " + face.name().toLowerCase(Locale.ROOT);
        if (face.equals(selection.buttons.get(pos))) {
            selection.buttons.remove(pos);
            info(player, "Removed button spot " + where + ".");
        } else {
            BlockFace previous = selection.buttons.put(pos, face);
            success(player, (previous == null ? "Added" : "Moved") + " button spot " + where
                    + ". Run it again on the same face to remove it.");
        }
        status(player, selection);
        return Command.SINGLE_SUCCESS;
    }

    private int spawn(Player player) {
        Selection selection = selectionIn(player);
        selection.spawns.add(player.getLocation());
        success(player, "Added mob spawn " + selection.spawns.size() + " at "
                + TemplateValidation.format(player.getLocation().toVector()) + ".");
        status(player, selection);
        return Command.SINGLE_SUCCESS;
    }

    private int clearSelection(Player player) {
        selections.remove(player.getUniqueId());
        success(player, "Template selection cleared.");
        return Command.SINGLE_SUCCESS;
    }

    private int save(Player player, CommandContext<CommandSourceStack> ctx) {
        Optional<TemplateType> type = type(player, ctx);
        if (type.isEmpty()) {
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || selection.pos1 == null || selection.pos2 == null) {
            error(player, "Set both corners first with /chutes template pos1 and pos2.");
            return 0;
        }
        if (!selection.world.equals(player.getWorld())) {
            error(player, "Your selection is in world " + selection.world.getName() + "; go there to save it.");
            return 0;
        }
        BlockVector min = selection.min();
        Location origin = new Location(selection.world, min.getX(), min.getY(), min.getZ());
        SpawnPoint entrance = selection.entrance == null ? null : new SpawnPoint(
                relative(selection.entrance, min), selection.entrance.getYaw(), selection.entrance.getPitch());
        List<ButtonSpot> buttons = selection.buttons.entrySet().stream()
                .map(e -> new ButtonSpot(e.getKey().clone().subtract(min).toBlockVector(), e.getValue()))
                .toList();
        List<Vector> spawns = selection.spawns.stream().map(s -> relative(s, min)).toList();

        try {
            FileTemplateStore.SaveResult result = store.save(type.get(), id, origin, selection.size(), entrance,
                    buttons, spawns);
            RoomTemplate saved = result.template();
            success(player, (result.replaced() ? "Replaced " : "Saved ") + describe(saved) + ".");
            for (String warning : result.warnings()) {
                player.sendMessage(Component.text("Warning: " + warning, NamedTextColor.GOLD));
            }
            if (type.get() != TemplateType.ARENA && buttons.size() < 4) {
                player.sendMessage(Component.text("Tip: a room is more fun with several button spots to pick from.",
                        NamedTextColor.GRAY));
            }
            plugin.getSLF4JLogger().info("{} saved template {}/{}", player.getName(), saved.type(), saved.id());
            return Command.SINGLE_SUCCESS;
        } catch (TemplateException e) {
            error(player, "Cannot save " + type.get().name().toLowerCase(Locale.ROOT) + " " + id + ": "
                    + e.getMessage());
            return 0;
        }
    }

    /** Offset from the min corner, centred on the block in x and z so markers do not sit on block edges. */
    private static Vector relative(Location location, BlockVector min) {
        return new Vector(location.getBlockX() + 0.5 - min.getX(), location.getY() - min.getY(),
                location.getBlockZ() + 0.5 - min.getZ());
    }

    private int list(CommandSender sender, TemplateType only) {
        for (TemplateType type : TemplateType.values()) {
            if (only != null && type != only) {
                continue;
            }
            List<RoomTemplate> own = store.savedTemplates(type);
            List<RoomTemplate> shown = own.isEmpty() ? store.builtinTemplates(type) : own;
            String label = type.name().toLowerCase(Locale.ROOT)
                    + (own.isEmpty() ? " (nothing saved, using built-ins)" : " (" + own.size() + " saved)");
            sender.sendMessage(Component.text(label + ":", NamedTextColor.YELLOW));
            for (RoomTemplate template : shown) {
                sender.sendMessage(Component.text("  " + describe(template), NamedTextColor.GRAY));
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private int delete(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Optional<TemplateType> type = type(sender, ctx);
        if (type.isEmpty()) {
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        if (id.startsWith(BuiltinTemplates.PREFIX)) {
            error(sender, "Built-in templates cannot be deleted; they are only used while nothing is saved.");
            return 0;
        }
        try {
            if (!store.delete(type.get(), id)) {
                error(sender, "No saved " + type.get().name().toLowerCase(Locale.ROOT) + " template called " + id + ".");
                return 0;
            }
        } catch (TemplateException e) {
            error(sender, e.getMessage());
            return 0;
        }
        success(sender, "Deleted " + type.get().name().toLowerCase(Locale.ROOT) + " template " + id + ".");
        if (store.savedTemplates(type.get()).isEmpty()) {
            info(sender, "No " + type.get().name().toLowerCase(Locale.ROOT)
                    + " templates are saved now, so the built-ins will be used.");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int paste(Player player, CommandContext<CommandSourceStack> ctx) {
        Optional<TemplateType> type = type(player, ctx);
        if (type.isEmpty()) {
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        Optional<RoomTemplate> found = store.get(type.get(), id);
        if (found.isEmpty()) {
            error(player, "No " + type.get().name().toLowerCase(Locale.ROOT) + " template called " + id + ".");
            return 0;
        }
        RoomTemplate template = found.get();
        Location origin = Regions.blockOrigin(player.getLocation());
        template.place(origin);

        // Load the pasted template as the selection, so it can be tweaked and saved again.
        selections.remove(player.getUniqueId());
        Selection selection = selectionIn(player);
        BlockVector size = template.size();
        selection.pos1 = new BlockVector(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
        selection.pos2 = new BlockVector(origin.getBlockX() + size.getBlockX() - 1,
                origin.getBlockY() + size.getBlockY() - 1, origin.getBlockZ() + size.getBlockZ() - 1);
        selection.entrance = template.entrance().at(origin);
        for (ButtonSpot spot : template.buttonSpots()) {
            selection.buttons.put(spot.block().clone().add(selection.pos1).toBlockVector(), spot.face());
        }
        for (Vector spawn : template.mobSpawns()) {
            selection.spawns.add(origin.clone().add(spawn));
        }
        player.teleport(selection.entrance);

        success(player, "Pasted " + describe(template) + " with its min corner at "
                + TemplateValidation.format(selection.pos1) + "; you are at its entrance.");
        info(player, "The paste is now your selection, so you can adjust markers and save it again.");
        return Command.SINGLE_SUCCESS;
    }

    private void status(Player player, Selection selection) {
        String size = selection.pos1 != null && selection.pos2 != null
                ? selection.size().getBlockX() + "x" + selection.size().getBlockY() + "x" + selection.size().getBlockZ()
                : "corners " + (selection.pos1 == null ? "-" : "1") + "/" + (selection.pos2 == null ? "-" : "2");
        player.sendMessage(Component.text("Selection: " + size + ", entrance " + (selection.entrance == null ? "not set" : "set")
                + ", " + selection.buttons.size() + " button spot(s), " + selection.spawns.size() + " mob spawn(s)",
                NamedTextColor.GRAY));
    }

    private static String describe(RoomTemplate template) {
        BlockVector size = template.size();
        String markers = template.type() == TemplateType.ARENA
                ? template.mobSpawns().size() + " mob spawns"
                : template.buttonSpots().size() + " button spots";
        return template.type().name().toLowerCase(Locale.ROOT) + " " + template.id() + " ("
                + size.getBlockX() + "x" + size.getBlockY() + "x" + size.getBlockZ() + ", " + markers + ")";
    }

    private static String name(Block block) {
        return block.getType().name().toLowerCase(Locale.ROOT);
    }

    private Optional<TemplateType> type(CommandSender sender, CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "type");
        try {
            return Optional.of(TemplateType.valueOf(raw.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            error(sender, "Unknown template type '" + raw + "'; use one of "
                    + Arrays.stream(TemplateType.values()).map(t -> t.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(", ")) + ".");
            return Optional.empty();
        }
    }

    private CompletableFuture<Suggestions> suggestTypes(CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder) {
        for (TemplateType type : TemplateType.values()) {
            String name = type.name().toLowerCase(Locale.ROOT);
            if (name.startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    private SuggestionProvider<CommandSourceStack> idSuggestions(boolean savedOnly) {
        return (ctx, builder) -> {
            TemplateType type;
            try {
                type = TemplateType.valueOf(StringArgumentType.getString(ctx, "type").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return builder.buildFuture();
            }
            List<RoomTemplate> templates = new ArrayList<>(store.savedTemplates(type));
            if (!savedOnly) {
                templates.addAll(BuiltinTemplates.of(type));
            }
            for (RoomTemplate template : templates) {
                if (template.id().startsWith(builder.getRemainingLowerCase())) {
                    builder.suggest(template.id());
                }
            }
            return builder.buildFuture();
        };
    }

    private static void success(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    private static void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.AQUA));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
