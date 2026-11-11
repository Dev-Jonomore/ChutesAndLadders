package mc.jonomore.chutes;

import java.util.Map;
import mc.jonomore.chutes.board.BoardGenerator;
import mc.jonomore.chutes.board.ChallengeType;
import mc.jonomore.chutes.challenge.ChallengeFactory;
import mc.jonomore.chutes.stats.StatsStore;
import mc.jonomore.chutes.world.BoardWorld;
import mc.jonomore.chutes.world.TemplateStore;

/** Everything the game layer depends on, built once in {@link ChutesPlugin}. */
public record Services(
        ChutesConfig config,
        BoardGenerator boardGenerator,
        BoardWorld boardWorld,
        TemplateStore templates,
        Map<ChallengeType, ChallengeFactory> challenges,
        StatsStore stats) {
}
