package mc.jonomore.chutes.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import mc.jonomore.chutes.ChutesConfig;
import mc.jonomore.chutes.board.ChallengeType;
import mc.jonomore.chutes.board.Transit;
import mc.jonomore.chutes.challenge.ChallengeResult;
import org.junit.jupiter.api.Test;

class DefaultTransitScoringTest {

    private static final ChutesConfig.Scoring SCORING = new ChutesConfig.Scoring(1, 1);

    private static int moved(int delta, boolean completed, double progress, long elapsed, long par) {
        return DefaultTransitScoring.roomsMoved(
                new Transit(delta, ChallengeType.MAZE), new ChallengeResult(completed, progress, elapsed, par), SCORING);
    }

    @Test
    void completedLadderGivesFullLength() {
        assertEquals(12, moved(12, true, 1, 100, 200));
    }

    @Test
    void failedLadderGivesProportionalGainWithMinimum() {
        assertEquals(7, moved(12, false, 0.6, 200, 200));
        assertEquals(1, moved(12, false, 0.0, 200, 200));
        assertEquals(12, moved(12, false, 1.0, 200, 200));
    }

    @Test
    void failedLadderGainIsCappedAtLength() {
        ChutesConfig.Scoring generous = new ChutesConfig.Scoring(20, 1);
        int moved = DefaultTransitScoring.roomsMoved(
                new Transit(5, ChallengeType.WAVES), new ChallengeResult(false, 0.1, 10, 10), generous);
        assertEquals(5, moved);
    }

    @Test
    void completedChuteScalesWithTime() {
        assertEquals(-5, moved(-9, true, 1, 100, 200));   // ceil(9 * 0.5) = 5
        assertEquals(-1, moved(-9, true, 1, 1, 2000));    // min regress 1
        assertEquals(-9, moved(-9, true, 1, 400, 200));   // capped at C
    }

    @Test
    void failedChuteGivesFullLength() {
        assertEquals(-9, moved(-9, false, 0.4, 200, 200));
    }
}
