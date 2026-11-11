package mc.jonomore.chutes.game;

import static net.kyori.adventure.text.Component.text;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import mc.jonomore.chutes.command.CommandModule;
import mc.jonomore.chutes.stats.RunRecord;
import mc.jonomore.chutes.stats.StatsStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** The play subcommands of /chutes (docs/DESIGN.md section 5). */
public final class GameCommands implements CommandModule {

    private static final String PLAY_PERMISSION = "chutes.play";
    private static final int TOP_LIMIT = 10;
    private static final int MAX_TEAM_SIZE_ARGUMENT = 64;

    private final GameManager manager;

    public GameCommands(GameManager manager) {
        this.manager = manager;
    }

    @FunctionalInterface
    private interface BoardAction {
        void run(Player player, @Nullable Integer rooms, @Nullable Long seed);
    }

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        Predicate<CommandSourceStack> play = source -> source.getSender().hasPermission(PLAY_PERMISSION);
        Predicate<CommandSourceStack> admin = source -> source.getSender().hasPermission(GameManager.ADMIN_PERMISSION);

        root.then(boardCommand("solo", play, manager::solo));
        root.then(boardCommand("create", play, manager::create));

        root.then(Commands.literal("join").requires(play)
                .then(Commands.argument("host", StringArgumentType.word())
                        .suggests(suggest(manager::lobbyHosts))
                        .executes(ctx -> asPlayer(ctx, player ->
                                manager.join(player, StringArgumentType.getString(ctx, "host"))))));

        root.then(Commands.literal("leave").requires(play)
                .executes(ctx -> asPlayer(ctx, manager::leave)));

        root.then(Commands.literal("team").requires(play)
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> asPlayer(ctx, player ->
                                manager.team(player, StringArgumentType.getString(ctx, "name"))))));

        root.then(Commands.literal("start").requires(play)
                .executes(ctx -> asPlayer(ctx, manager::start)));

        root.then(Commands.literal("stop").requires(play.or(admin))
                .executes(ctx -> asSender(ctx, sender -> manager.stop(sender, null)))
                .then(Commands.argument("player", StringArgumentType.word()).requires(admin)
                        .suggests(suggest(() -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()))
                        .executes(ctx -> asSender(ctx, sender ->
                                manager.stop(sender, StringArgumentType.getString(ctx, "player"))))));

        root.then(Commands.literal("top").requires(play)
                .then(topCommand("plies", StatsStore.Metric.PLIES))
                .then(topCommand("time", StatsStore.Metric.TIME)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> boardCommand(
            String name, Predicate<CommandSourceStack> requirement, BoardAction action) {
        return Commands.literal(name).requires(requirement)
                .executes(ctx -> asPlayer(ctx, player -> action.run(player, null, null)))
                .then(Commands.argument("rooms", IntegerArgumentType.integer(GameManager.MIN_ROOMS, GameManager.MAX_ROOMS))
                        .executes(ctx -> asPlayer(ctx, player ->
                                action.run(player, IntegerArgumentType.getInteger(ctx, "rooms"), null)))
                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                .executes(ctx -> asPlayer(ctx, player -> action.run(player,
                                        IntegerArgumentType.getInteger(ctx, "rooms"),
                                        LongArgumentType.getLong(ctx, "seed"))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> topCommand(String name, StatsStore.Metric metric) {
        return Commands.literal(name)
                .executes(ctx -> top(ctx, metric, null, null))
                .then(Commands.argument("rooms", IntegerArgumentType.integer(GameManager.MIN_ROOMS, GameManager.MAX_ROOMS))
                        .executes(ctx -> top(ctx, metric, IntegerArgumentType.getInteger(ctx, "rooms"), null))
                        .then(Commands.argument("team-size", IntegerArgumentType.integer(1, MAX_TEAM_SIZE_ARGUMENT))
                                .executes(ctx -> top(ctx, metric, IntegerArgumentType.getInteger(ctx, "rooms"),
                                        IntegerArgumentType.getInteger(ctx, "team-size")))));
    }

    private int top(CommandContext<CommandSourceStack> ctx, StatsStore.Metric metric,
            @Nullable Integer rooms, @Nullable Integer teamSize) {
        CommandSender sender = ctx.getSource().getSender();
        Player player = playerOf(ctx.getSource());
        int boardRooms = rooms != null ? rooms
                : player != null ? manager.roomsFor(player) : manager.config().board().defaultRooms();
        int size = teamSize != null ? teamSize : player != null ? manager.teamSizeFor(player) : 1;

        List<RunRecord> runs;
        try {
            runs = manager.stats().top(boardRooms, size, metric, TOP_LIMIT);
        } catch (RuntimeException e) {
            sender.sendMessage(GameText.error("Could not read the leaderboard."));
            return 0;
        }
        String by = metric == StatsStore.Metric.PLIES ? "fewest plies" : "fastest time";
        sender.sendMessage(GameText.info("Top " + TOP_LIMIT + " by " + by + ": " + boardRooms + " rooms, team size " + size));
        if (runs.isEmpty()) {
            sender.sendMessage(text("  No finished runs yet.", NamedTextColor.GRAY));
            return Command.SINGLE_SUCCESS;
        }
        int rank = 1;
        for (RunRecord run : runs) {
            Component line = text("  " + rank++ + ". ", NamedTextColor.GOLD)
                    .append(text(String.join(", ", run.names()), NamedTextColor.WHITE))
                    .append(text(" - ", NamedTextColor.DARK_GRAY))
                    .append(text(run.plies() + " plies", metric == StatsStore.Metric.PLIES ? NamedTextColor.YELLOW : NamedTextColor.GRAY))
                    .append(text(", ", NamedTextColor.DARK_GRAY))
                    .append(text(MovementRules.clock(run.ticks()), metric == StatsStore.Metric.TIME ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
            sender.sendMessage(line);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int asPlayer(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        Player player = playerOf(ctx.getSource());
        if (player == null) {
            ctx.getSource().getSender().sendMessage(GameText.error("Only players can do that."));
            return 0;
        }
        try {
            action.accept(player);
        } catch (GameException e) {
            ctx.getSource().getSender().sendMessage(GameText.error(e.getMessage()));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int asSender(CommandContext<CommandSourceStack> ctx, Consumer<CommandSender> action) {
        CommandSender sender = ctx.getSource().getSender();
        try {
            action.accept(playerOf(ctx.getSource()) instanceof Player player ? player : sender);
        } catch (GameException e) {
            sender.sendMessage(GameText.error(e.getMessage()));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private static @Nullable Player playerOf(CommandSourceStack source) {
        if (source.getExecutor() instanceof Player player) {
            return player;
        }
        return source.getSender() instanceof Player player ? player : null;
    }

    private static SuggestionProvider<CommandSourceStack> suggest(Supplier<Collection<String>> names) {
        return (CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) -> {
            String typed = builder.getRemainingLowerCase();
            for (String name : names.get()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };
    }
}
