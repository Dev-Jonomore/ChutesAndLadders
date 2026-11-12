package mc.jonomore.chutes;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import mc.jonomore.chutes.board.ChallengeType;
import mc.jonomore.chutes.board.RandomBoardGenerator;
import mc.jonomore.chutes.board.TransitScoring;
import mc.jonomore.chutes.challenge.ChallengeFactory;
import mc.jonomore.chutes.challenge.maze.MazeChallengeFactory;
import mc.jonomore.chutes.challenge.waves.WaveChallengeFactory;
import mc.jonomore.chutes.command.CommandModule;
import mc.jonomore.chutes.game.GameCommands;
import mc.jonomore.chutes.game.GameManager;
import mc.jonomore.chutes.stats.YamlStatsStore;
import mc.jonomore.chutes.world.FileTemplateStore;
import mc.jonomore.chutes.world.TemplateCommands;
import mc.jonomore.chutes.world.VoidBoardWorld;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/** Entry point. Builds {@link Services} and registers the /chutes command tree. */
public final class ChutesPlugin extends JavaPlugin {

    private @Nullable GameManager games;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Services services;
        try {
            ChutesConfig config = ChutesConfig.load(getConfig());

            Map<ChallengeType, ChallengeFactory> challenges = new EnumMap<>(ChallengeType.class);
            for (ChallengeFactory factory : List.of(new MazeChallengeFactory(), new WaveChallengeFactory())) {
                challenges.put(factory.type(), factory);
            }

            services = new Services(
                    config,
                    new RandomBoardGenerator(),
                    new VoidBoardWorld(this, config),
                    new FileTemplateStore(this),
                    challenges,
                    new YamlStatsStore(getDataFolder().toPath().resolve("stats.yml")));
        } catch (IllegalArgumentException e) {
            getSLF4JLogger().error("Invalid config.yml: {}", e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        GameManager manager = new GameManager(this, services);
        manager.setTransitResolver(TransitScoring::roomsMoved);
        games = manager;

        List<CommandModule> modules = List.of(
                new GameCommands(manager),
                new TemplateCommands(this, services.templates()));

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("chutes");
            modules.forEach(module -> module.register(root));
            event.registrar().register(root.build(), "Chutes and Ladders", List.of("cl"));
        });
    }

    @Override
    public void onDisable() {
        if (games != null) {
            games.shutdown();
            games = null;
        }
    }
}
