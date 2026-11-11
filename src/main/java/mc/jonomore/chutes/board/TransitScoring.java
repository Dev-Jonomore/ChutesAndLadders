package mc.jonomore.chutes.board;

import mc.jonomore.chutes.ChutesConfig;
import mc.jonomore.chutes.challenge.ChallengeResult;

/** The transit scoring table from docs/DESIGN.md section 1. */
public final class TransitScoring {

    /** Absorbs float error so that e.g. 3 * (2/3.0) floors to 2, not 1. */
    private static final double EPSILON = 1e-9;

    private TransitScoring() {
    }

    /**
     * Signed number of rooms the pawn moves after a transit challenge: positive for a ladder,
     * negative (or 0) for a chute. Clamping to the board and the checkpoint is the caller's job.
     */
    public static int roomsMoved(Transit transit, ChallengeResult result, ChutesConfig.Scoring scoring) {
        int length = transit.length();
        if (transit.isLadder()) {
            if (result.completed()) {
                return length;
            }
            int reached = (int) Math.floor(length * result.progress() + EPSILON);
            return Math.min(length, Math.max(scoring.ladderMinGain(), reached));
        }
        if (!result.completed()) {
            return -length;
        }
        long elapsed = Math.max(0, result.elapsedTicks());
        long scaled = Math.ceilDiv(length * elapsed, result.parTicks());
        // Cap at the length last, so a min-regress larger than the chute never exceeds it.
        long regress = Math.min(length, Math.max(scoring.chuteMinRegress(), scaled));
        return (int) -regress;
    }
}
