package mc.jonomore.chutes.board;

import org.jspecify.annotations.Nullable;

/**
 * @param transit non-null exactly when kind is LADDER or CHUTE
 */
public record BoardRoom(int index, RoomKind kind, @Nullable Transit transit) {

    public BoardRoom {
        boolean needsTransit = kind == RoomKind.LADDER || kind == RoomKind.CHUTE;
        if (needsTransit != (transit != null)) {
            throw new IllegalArgumentException("transit must be set exactly for LADDER and CHUTE rooms");
        }
    }
}
