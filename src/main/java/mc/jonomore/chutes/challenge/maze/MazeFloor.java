package mc.jonomore.chutes.challenge.maze;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * One floor of a maze: a {@code cells x cells} grid with passages between neighbouring cells,
 * the cell players enter the floor at and the cell that leads on to the next floor.
 * Cells are addressed by {@code (x, z)}, both in {@code 0..cells-1}.
 */
public final class MazeFloor {

    /** A grid step. North is -z, as in Minecraft. */
    public enum Direction {
        NORTH(0, -1),
        SOUTH(0, 1),
        EAST(1, 0),
        WEST(-1, 0);

        public final int dx;
        public final int dz;

        Direction(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }
    }

    public record Cell(int x, int z) {

        public Cell step(Direction direction) {
            return new Cell(x + direction.dx, z + direction.dz);
        }
    }

    private final int cells;
    /** eastOpen[x][z]: passage between (x, z) and (x + 1, z). */
    private final boolean[][] eastOpen;
    /** southOpen[x][z]: passage between (x, z) and (x, z + 1). */
    private final boolean[][] southOpen;
    private Cell start;
    private Cell exit;

    MazeFloor(int cells) {
        this.cells = cells;
        this.eastOpen = new boolean[cells][cells];
        this.southOpen = new boolean[cells][cells];
    }

    public int cells() {
        return cells;
    }

    public Cell start() {
        return start;
    }

    public Cell exit() {
        return exit;
    }

    void setEnds(Cell start, Cell exit) {
        this.start = start;
        this.exit = exit;
    }

    public boolean contains(Cell cell) {
        return cell.x() >= 0 && cell.z() >= 0 && cell.x() < cells && cell.z() < cells;
    }

    /** Whether there is a passage from {@code cell} towards {@code direction}. */
    public boolean isOpen(Cell cell, Direction direction) {
        Cell next = cell.step(direction);
        if (!contains(cell) || !contains(next)) {
            return false;
        }
        return switch (direction) {
            case EAST -> eastOpen[cell.x()][cell.z()];
            case WEST -> eastOpen[next.x()][next.z()];
            case SOUTH -> southOpen[cell.x()][cell.z()];
            case NORTH -> southOpen[next.x()][next.z()];
        };
    }

    void open(Cell cell, Direction direction) {
        Cell next = cell.step(direction);
        switch (direction) {
            case EAST -> eastOpen[cell.x()][cell.z()] = true;
            case WEST -> eastOpen[next.x()][next.z()] = true;
            case SOUTH -> southOpen[cell.x()][cell.z()] = true;
            case NORTH -> southOpen[next.x()][next.z()] = true;
        }
    }

    public int passageCount() {
        int count = 0;
        for (int x = 0; x < cells; x++) {
            for (int z = 0; z < cells; z++) {
                if (eastOpen[x][z]) {
                    count++;
                }
                if (southOpen[x][z]) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Walking distance from {@code from} to every cell, or -1 where unreachable. Indexed [x][z]. */
    public int[][] distancesFrom(Cell from) {
        int[][] distance = new int[cells][cells];
        for (int[] column : distance) {
            Arrays.fill(column, -1);
        }
        Deque<Cell> queue = new ArrayDeque<>();
        distance[from.x()][from.z()] = 0;
        queue.add(from);
        while (!queue.isEmpty()) {
            Cell cell = queue.poll();
            for (Direction direction : Direction.values()) {
                if (!isOpen(cell, direction)) {
                    continue;
                }
                Cell next = cell.step(direction);
                if (distance[next.x()][next.z()] < 0) {
                    distance[next.x()][next.z()] = distance[cell.x()][cell.z()] + 1;
                    queue.add(next);
                }
            }
        }
        return distance;
    }
}
