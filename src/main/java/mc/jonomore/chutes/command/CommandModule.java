package mc.jonomore.chutes.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/** Adds subcommands under the shared /chutes root. */
public interface CommandModule {

    void register(LiteralArgumentBuilder<CommandSourceStack> root);
}
