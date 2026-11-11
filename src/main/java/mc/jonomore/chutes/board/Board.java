package mc.jonomore.chutes.board;

import java.util.List;

/**
 * Rooms 0..size()-1. Index size() is the finish.
 */
public record Board(long seed, List<BoardRoom> rooms) {

    public Board {
        rooms = List.copyOf(rooms);
    }

    public int size() {
        return rooms.size();
    }

    public BoardRoom room(int index) {
        return rooms.get(index);
    }
}
