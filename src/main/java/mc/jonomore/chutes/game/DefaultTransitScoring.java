package mc.jonomore.chutes.game;

import mc.jonomore.chutes.ChutesConfig;
import mc.jonomore.chutes.board.Transit;
import mc.jonomore.chutes.challenge.ChallengeResult;

/**
 * The game's own copy of the transit scoring table, used until the integrator swaps in
 * {@code board.TransitScoring} through {@link GameManager#setTransitResolver(TransitResolver)}.
 */
final class DefaultTransitScoring {

    private DefaultTransitScoring() {
    }

    static int roomsMoved(Transit transit, ChallengeResult result, ChutesConfig.Scoring scoring) {
        int length = transit.length();
        if (transit.isLadder()) {
            if (result.completed()) {
                return length;
            }
            int gain = Math.max(scoring.ladderMinGain(), (int) Math.floor(length * result.progress()));
            return Math.min(length, gain);
        }
        if (!result.completed()) {
            return -length;
        }
        long scaled = (long) Math.ceil(length * (double) result.elapsedTicks() / result.parTicks());
        long regress = Math.min(length, Math.max(scoring.chuteMinRegress(), scaled));
        return -(int) regress;
    }
}
