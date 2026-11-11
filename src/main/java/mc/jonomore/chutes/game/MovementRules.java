package mc.jonomore.chutes.game;

import java.util.random.RandomGenerator;

/** Pure board movement rules (docs/DESIGN.md section 1). Rooms are 0..boardSize-1, boardSize is the finish. */
public final class MovementRules {

    /** Salts so the template choice and the challenge seed of a room are independent streams. */
    static final long TEMPLATE_SALT = 0x5EED_7E3L;
    static final long CHALLENGE_SALT = 0xC4A1_1E9EL;

    private MovementRules() {
    }

    /** A die roll in 1..maxRoll. */
    public static int roll(RandomGenerator random, int maxRoll) {
        return 1 + random.nextInt(Math.max(1, maxRoll));
    }

    /** Destination of a normal exit; never past the finish. */
    public static int afterRoll(int from, int roll, int boardSize) {
        return (int) Math.min((long) from + roll, boardSize);
    }

    /**
     * Destination of a transit: clamped to [0, boardSize], then, for a backwards move, never
     * below the checkpoint floor (or the current room, should the floor somehow be above it).
     *
     * @param checkpointFloor 0 when checkpoints are disabled
     */
    public static int afterTransit(int from, int roomsMoved, int boardSize, int checkpointFloor) {
        int destination = Math.clamp((long) from + roomsMoved, 0, boardSize);
        if (roomsMoved < 0) {
            destination = Math.max(destination, Math.min(checkpointFloor, from));
        }
        return destination;
    }

    /** The checkpoint after arriving in a room. */
    public static int checkpointAfterArrival(int checkpoint, int roomIndex, boolean breakRoom, boolean checkpointsEnabled) {
        return checkpointsEnabled && breakRoom ? Math.max(checkpoint, roomIndex) : checkpoint;
    }

    public static boolean isFinish(int roomIndex, int boardSize) {
        return roomIndex >= boardSize;
    }

    /** A well-mixed seed for one room of one board (SplitMix64 finalizer). */
    public static long derivedSeed(long boardSeed, int roomIndex, long salt) {
        long z = boardSeed + salt + 0x9E3779B97F4A7C15L * (roomIndex + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** m:ss, or h:mm:ss for long games. */
    public static String clock(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        long secs = seconds % 60;
        return hours > 0
                ? "%d:%02d:%02d".formatted(hours, minutes, secs)
                : "%d:%02d".formatted(minutes, secs);
    }
}
