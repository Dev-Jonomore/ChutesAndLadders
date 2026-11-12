package mc.jonomore.chutes.challenge.maze;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import mc.jonomore.chutes.challenge.maze.MazeFloor.Cell;
import mc.jonomore.chutes.challenge.maze.MazeFloor.Direction;

/**
 * Generates a stack of perfect mazes, deterministic for a seed. Floors are in travel order:
 * floor 0 is where players start, the exit of floor {@code k} is the start of floor {@code k + 1},
 * and the exit of the last floor is the goal.
 */
public final class MazeGenerator {

    private MazeGenerator() {
    }

    /** A square maze, {@code cells} on each side. */
    public static List<MazeFloor> generate(int cells, int floors, long seed) {
        return generate(cells, cells, floors, seed);
    }

    public static List<MazeFloor> generate(int cellsX, int cellsZ, int floors, long seed) {
        if (cellsX < 2 || cellsZ < 2) {
            throw new IllegalArgumentException("a maze needs at least 2 x 2 cells, got " + cellsX + " x " + cellsZ);
        }
        if (floors < 1) {
            throw new IllegalArgumentException("floors must be at least 1, got " + floors);
        }
        Random random = new Random(seed);
        int lastX = cellsX - 1;
        int lastZ = cellsZ - 1;
        Cell start = switch (random.nextInt(4)) {
            case 0 -> new Cell(0, 0);
            case 1 -> new Cell(lastX, 0);
            case 2 -> new Cell(0, lastZ);
            default -> new Cell(lastX, lastZ);
        };

        List<MazeFloor> result = new ArrayList<>(floors);
        for (int i = 0; i < floors; i++) {
            MazeFloor floor = carve(cellsX, cellsZ, random);
            Cell exit = farthest(floor, start);
            floor.setEnds(start, exit);
            result.add(floor);
            start = exit;
        }
        return List.copyOf(result);
    }

    /** Iterative recursive-backtracker: yields a spanning tree of the grid, i.e. a perfect maze. */
    private static MazeFloor carve(int cellsX, int cellsZ, Random random) {
        MazeFloor floor = new MazeFloor(cellsX, cellsZ);
        boolean[][] visited = new boolean[cellsX][cellsZ];
        Deque<Cell> stack = new ArrayDeque<>();
        Cell first = new Cell(random.nextInt(cellsX), random.nextInt(cellsZ));
        visited[first.x()][first.z()] = true;
        stack.push(first);
        List<Direction> directions = new ArrayList<>(Arrays.asList(Direction.values()));
        while (!stack.isEmpty()) {
            Cell cell = stack.peek();
            Collections.shuffle(directions, random);
            boolean moved = false;
            for (Direction direction : directions) {
                Cell next = cell.step(direction);
                if (floor.contains(next) && !visited[next.x()][next.z()]) {
                    floor.open(cell, direction);
                    visited[next.x()][next.z()] = true;
                    stack.push(next);
                    moved = true;
                    break;
                }
            }
            if (!moved) {
                stack.pop();
            }
        }
        return floor;
    }

    /** The cell farthest from {@code from} by walking distance; ties go to the first in x, z order. */
    private static Cell farthest(MazeFloor floor, Cell from) {
        int[][] distance = floor.distancesFrom(from);
        Cell best = from;
        int bestDistance = 0;
        for (int x = 0; x < floor.cellsX(); x++) {
            for (int z = 0; z < floor.cellsZ(); z++) {
                if (distance[x][z] > bestDistance) {
                    bestDistance = distance[x][z];
                    best = new Cell(x, z);
                }
            }
        }
        return best;
    }
}
