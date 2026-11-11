package mc.jonomore.chutes.board;

/**
 * A ladder or chute.
 *
 * @param delta rooms moved on the full result; positive for a ladder, negative for a chute, never 0
 */
public record Transit(int delta, ChallengeType challenge) {

    public Transit {
        if (delta == 0) {
            throw new IllegalArgumentException("delta must not be 0");
        }
    }

    public boolean isLadder() {
        return delta > 0;
    }

    /** Length in rooms, always positive. */
    public int length() {
        return Math.abs(delta);
    }
}
