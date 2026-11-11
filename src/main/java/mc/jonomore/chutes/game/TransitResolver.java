package mc.jonomore.chutes.game;

import mc.jonomore.chutes.ChutesConfig;
import mc.jonomore.chutes.board.Transit;
import mc.jonomore.chutes.challenge.ChallengeResult;

/**
 * Turns a finished transit challenge into signed rooms moved (docs/DESIGN.md section 1).
 * Same shape as {@code board.TransitScoring.roomsMoved}, so it can be passed as a method reference.
 * Clamping to the board and the checkpoint is not the resolver's job.
 */
@FunctionalInterface
public interface TransitResolver {

    int roomsMoved(Transit transit, ChallengeResult result, ChutesConfig.Scoring scoring);
}
