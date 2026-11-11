package mc.jonomore.chutes.challenge;

/**
 * One running transit challenge for one pawn. The challenge owns its own scheduling and
 * listeners, and unregisters them when it ends. The challenge teleports participants in on
 * {@link #start()}; the game teleports them away after {@code onFinish}.
 */
public interface Challenge {

    /** Build the arena, move participants in, start the timer. Called once, main thread. */
    void start();

    /** Current progress, 0..1. */
    double progress();

    /** Ticks left before the challenge fails, for the HUD. */
    long remainingTicks();

    /** Stop immediately and clean up blocks, entities and listeners without calling onFinish. */
    void abort();
}
