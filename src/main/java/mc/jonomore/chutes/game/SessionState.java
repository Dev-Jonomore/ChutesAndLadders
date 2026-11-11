package mc.jonomore.chutes.game;

enum SessionState {
    /** Players gather; nothing is built, nobody's state is touched. */
    LOBBY,
    /** The board is being placed; players are still where they were. */
    STARTING,
    /** Pawns are playing. */
    RUNNING,
    /** Game over (or stopped); players are restored and the board is being cleared. */
    ENDING,
    /** Fully cleaned up and forgotten. */
    CLOSED
}
