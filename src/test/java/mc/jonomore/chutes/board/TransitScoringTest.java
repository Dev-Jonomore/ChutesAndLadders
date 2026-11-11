package mc.jonomore.chutes.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import mc.jonomore.chutes.ChutesConfig.Scoring;
import mc.jonomore.chutes.challenge.ChallengeResult;
import org.junit.jupiter.api.Test;

class TransitScoringTest {

    private static final Scoring DEFAULT = new Scoring(1, 1);
    private static final Scoring NO_MINIMUMS = new Scoring(0, 0);

    private static Transit ladder(int length) {
        return new Transit(length, ChallengeType.MAZE);
    }

    private static Transit chute(int length) {
        return new Transit(-length, ChallengeType.WAVES);
    }

    private static ChallengeResult completed(long elapsed, long par) {
        return new ChallengeResult(true, 1.0, elapsed, par);
    }

    private static ChallengeResult failed(double progress) {
        return new ChallengeResult(false, progress, 600, 600);
    }

    private static int moved(Transit transit, ChallengeResult result, Scoring scoring) {
        return TransitScoring.roomsMoved(transit, result, scoring);
    }

    // Ladder, completed: +L regardless of time.

    @Test
    void completedLadderMovesFullLength() {
        assertEquals(7, moved(ladder(7), completed(10, 600), DEFAULT));
        assertEquals(7, moved(ladder(7), completed(6000, 600), DEFAULT));
        assertEquals(1, moved(ladder(1), completed(600, 600), new Scoring(5, 0)));
    }

    // Ladder, failed: +max(min-gain, floor(L * progress)), capped at L.

    @Test
    void failedLadderFloorsProgress() {
        assertEquals(5, moved(ladder(10), failed(0.5), DEFAULT));
        assertEquals(5, moved(ladder(10), failed(0.59), DEFAULT));
        assertEquals(9, moved(ladder(10), failed(0.99), DEFAULT));
        assertEquals(2, moved(ladder(3), failed(2 / 3.0), DEFAULT));
        assertEquals(7, moved(ladder(10), failed(0.7), DEFAULT));
    }

    @Test
    void failedLadderUsesMinGain() {
        assertEquals(1, moved(ladder(10), failed(0.0), DEFAULT));
        assertEquals(1, moved(ladder(10), failed(0.05), DEFAULT));
        assertEquals(3, moved(ladder(10), failed(0.1), new Scoring(3, 1)));
        assertEquals(4, moved(ladder(10), failed(0.4), new Scoring(3, 1)));
    }

    @Test
    void failedLadderWithZeroMinGainCanMoveNothing() {
        assertEquals(0, moved(ladder(10), failed(0.0), NO_MINIMUMS));
        assertEquals(0, moved(ladder(10), failed(0.09), NO_MINIMUMS));
    }

    @Test
    void failedLadderIsCappedAtLength() {
        assertEquals(4, moved(ladder(4), failed(0.1), new Scoring(10, 0)));
        // Failed with full progress (e.g. timer hit on the last floor) still never exceeds L.
        assertEquals(4, moved(ladder(4), failed(1.0), DEFAULT));
    }

    // Chute, completed: -clamp(ceil(C * elapsed / par), min-regress, C).

    @Test
    void completedChuteScalesWithTime() {
        assertEquals(-5, moved(chute(10), completed(300, 600), DEFAULT));
        assertEquals(-6, moved(chute(10), completed(301, 600), DEFAULT)); // ceil
        assertEquals(-1, moved(chute(10), completed(1, 600), DEFAULT));
        assertEquals(-10, moved(chute(10), completed(600, 600), DEFAULT));
    }

    @Test
    void completedChuteIsClampedToLength() {
        assertEquals(-10, moved(chute(10), completed(6000, 600), DEFAULT));
    }

    @Test
    void completedChuteUsesMinRegress() {
        assertEquals(-1, moved(chute(10), completed(0, 600), DEFAULT));
        assertEquals(-3, moved(chute(10), completed(60, 600), new Scoring(1, 3)));
        assertEquals(-4, moved(chute(10), completed(240, 600), new Scoring(1, 3)));
    }

    @Test
    void completedChuteWithZeroMinRegressCanMoveNothing() {
        assertEquals(0, moved(chute(10), completed(0, 600), NO_MINIMUMS));
    }

    @Test
    void completedChuteMinRegressNeverExceedsLength() {
        assertEquals(-2, moved(chute(2), completed(0, 600), new Scoring(1, 5)));
    }

    // Chute, failed: -C.

    @Test
    void failedChuteMovesFullLength() {
        assertEquals(-8, moved(chute(8), failed(0.0), DEFAULT));
        assertEquals(-8, moved(chute(8), failed(0.9), NO_MINIMUMS));
    }
}
