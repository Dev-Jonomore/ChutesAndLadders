package mc.jonomore.chutes.game;

/** A rejected game command; the message is shown to the player as is. */
public final class GameException extends RuntimeException {

    public GameException(String message) {
        super(message, null, false, false);
    }
}
