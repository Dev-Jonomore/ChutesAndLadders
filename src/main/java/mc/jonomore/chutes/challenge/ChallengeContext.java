package mc.jonomore.chutes.challenge;

import java.util.List;
import java.util.function.Consumer;
import mc.jonomore.chutes.ChutesConfig;
import mc.jonomore.chutes.board.Transit;
import mc.jonomore.chutes.world.TemplateStore;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Everything a challenge needs to run.
 *
 * @param participants online members of the pawn when the transit started
 * @param origin       min corner of the arena region reserved for this challenge: 128 x 128
 *                     blocks in +x/+z, free from origin.y to the world top
 * @param seed         derived from board seed + room index; same transit, same seed
 * @param onFinish     must be called exactly once, on the main thread, after the challenge
 *                     has cleaned up its blocks and entities. Not called after {@link Challenge#abort()}.
 */
public record ChallengeContext(
        Plugin plugin,
        ChutesConfig config,
        TemplateStore templates,
        List<Player> participants,
        Location origin,
        Transit transit,
        long seed,
        Consumer<ChallengeResult> onFinish) {

    public ChallengeContext {
        participants = List.copyOf(participants);
    }
}
