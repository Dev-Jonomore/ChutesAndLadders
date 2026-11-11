package mc.jonomore.chutes.game;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import mc.jonomore.chutes.board.ChallengeType;
import mc.jonomore.chutes.board.Transit;
import mc.jonomore.chutes.challenge.Challenge;
import org.jspecify.annotations.Nullable;

/** A pawn's running ladder or chute challenge. */
final class ActiveTransit {

    /** Identifies this transit so a late or duplicate onFinish is ignored. */
    final int token;
    final int fromRoom;
    final Transit transit;
    /** The type of the factory actually used, which may differ from the board's on fallback. */
    final ChallengeType type;
    /** Players handed to the challenge; the challenge owns them (combat, inventory) until it ends. */
    final Set<UUID> participants;
    @Nullable Challenge challenge;

    ActiveTransit(int token, int fromRoom, Transit transit, ChallengeType type, Set<UUID> participants) {
        this.token = token;
        this.fromRoom = fromRoom;
        this.transit = transit;
        this.type = type;
        this.participants = new HashSet<>(participants);
    }
}
