package mc.jonomore.chutes.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class MovementRulesTest {

    @Test
    void rollStaysInRange() {
        SplittableRandom random = new SplittableRandom(1);
        boolean sawOne = false;
        boolean sawMax = false;
        for (int i = 0; i < 1000; i++) {
            int roll = MovementRules.roll(random, 6);
            assertTrue(roll >= 1 && roll <= 6, "roll " + roll);
            sawOne |= roll == 1;
            sawMax |= roll == 6;
        }
        assertTrue(sawOne && sawMax);
        assertEquals(1, MovementRules.roll(random, 1));
    }

    @Test
    void rollNeverPassesTheFinish() {
        assertEquals(7, MovementRules.afterRoll(3, 4, 50));
        assertEquals(50, MovementRules.afterRoll(48, 6, 50));
        assertEquals(50, MovementRules.afterRoll(44, 6, 50));
    }

    @Test
    void transitClampsToBoard() {
        assertEquals(15, MovementRules.afterTransit(5, 10, 50, 0));
        assertEquals(50, MovementRules.afterTransit(45, 12, 50, 0));
        assertEquals(0, MovementRules.afterTransit(3, -9, 50, 0));
    }

    @Test
    void chuteStopsAtCheckpoint() {
        assertEquals(20, MovementRules.afterTransit(25, -9, 50, 20));
        assertEquals(21, MovementRules.afterTransit(25, -4, 50, 20));
        // A floor above the current room (cannot happen in play) never pushes a pawn forward.
        assertEquals(12, MovementRules.afterTransit(12, -5, 50, 30));
    }

    @Test
    void checkpointDoesNotLimitLadders() {
        assertEquals(30, MovementRules.afterTransit(25, 5, 50, 40));
    }

    @Test
    void checkpointOnlyMovesOnBreakRoomsWhenEnabled() {
        assertEquals(10, MovementRules.checkpointAfterArrival(0, 10, true, true));
        assertEquals(0, MovementRules.checkpointAfterArrival(0, 10, true, false));
        assertEquals(0, MovementRules.checkpointAfterArrival(0, 11, false, true));
        assertEquals(20, MovementRules.checkpointAfterArrival(20, 10, true, true));
    }

    @Test
    void finishAtOrPastBoardSize() {
        assertFalse(MovementRules.isFinish(49, 50));
        assertTrue(MovementRules.isFinish(50, 50));
        assertTrue(MovementRules.isFinish(51, 50));
    }

    @Test
    void derivedSeedsAreStableAndDistinct() {
        assertEquals(MovementRules.derivedSeed(42, 7, MovementRules.CHALLENGE_SALT),
                MovementRules.derivedSeed(42, 7, MovementRules.CHALLENGE_SALT));
        assertNotEquals(MovementRules.derivedSeed(42, 7, MovementRules.CHALLENGE_SALT),
                MovementRules.derivedSeed(42, 8, MovementRules.CHALLENGE_SALT));
        assertNotEquals(MovementRules.derivedSeed(42, 7, MovementRules.CHALLENGE_SALT),
                MovementRules.derivedSeed(42, 7, MovementRules.TEMPLATE_SALT));
    }

    @Test
    void clockFormats() {
        assertEquals("0:00", MovementRules.clock(0));
        assertEquals("1:05", MovementRules.clock(65 * 20));
        assertEquals("1:00:01", MovementRules.clock(3601 * 20));
    }
}
