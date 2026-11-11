package mc.jonomore.chutes.board;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import mc.jonomore.chutes.ChutesConfig;

/**
 * Generates boards per docs/DESIGN.md section 2.
 *
 * <p>All randomness comes from one {@link Random} seeded with the board seed, so the same settings,
 * room count and seed always give the same board. Placement is a single pass over the shuffled
 * candidate rooms, so it always terminates; when the densities cannot be met, as many transits as
 * fit are placed.
 */
public final class RandomBoardGenerator implements BoardGenerator {

    private static final int MIN_ROOMS = 5;

    public RandomBoardGenerator() {
    }

    @Override
    public Board generate(ChutesConfig.BoardSettings settings, int rooms, long seed) {
        if (rooms < MIN_ROOMS) {
            throw new IllegalArgumentException("a board needs at least " + MIN_ROOMS + " rooms, got " + rooms);
        }
        return new Placement(settings, rooms, new Random(seed)).run(seed);
    }

    private static final class Placement {

        private final ChutesConfig.BoardSettings settings;
        private final int n;
        private final Random random;
        private final RoomKind[] kinds;
        private final Transit[] transits;
        /** Rooms some transit lands on; they must stay NORMAL or BREAK. */
        private final boolean[] destination;

        Placement(ChutesConfig.BoardSettings settings, int n, Random random) {
            this.settings = settings;
            this.n = n;
            this.random = random;
            this.kinds = new RoomKind[n];
            this.transits = new Transit[n];
            this.destination = new boolean[n];
            for (int i = 0; i < n; i++) {
                kinds[i] = i > 0 && i % settings.breakInterval() == 0 ? RoomKind.BREAK : RoomKind.NORMAL;
            }
        }

        Board run(long seed) {
            List<Integer> candidates = new ArrayList<>();
            for (int i = Math.max(1, settings.safeStart()); i < n; i++) {
                if (kinds[i] == RoomKind.NORMAL) {
                    candidates.add(i);
                }
            }
            Collections.shuffle(candidates, random);

            int laddersLeft = (int) Math.round(settings.ladderDensity() * n);
            int chutesLeft = (int) Math.round(settings.chuteDensity() * n);

            for (int index : candidates) {
                if (laddersLeft + chutesLeft == 0) {
                    break;
                }
                // Pick the type in proportion to what is still wanted, then fall back to the other.
                boolean ladderFirst = random.nextInt(laddersLeft + chutesLeft) < laddersLeft;
                for (boolean ladder : new boolean[] {ladderFirst, !ladderFirst}) {
                    int left = ladder ? laddersLeft : chutesLeft;
                    if (left > 0 && tryPlace(index, ladder)) {
                        if (ladder) {
                            laddersLeft--;
                        } else {
                            chutesLeft--;
                        }
                        break;
                    }
                }
            }

            List<BoardRoom> result = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                result.add(new BoardRoom(i, kinds[i], transits[i]));
            }
            return new Board(seed, result);
        }

        private boolean tryPlace(int index, boolean ladder) {
            if (destination[index]) {
                return false;
            }
            if (!ladder && chuteRunWith(index) > settings.maxRoll() - 1) {
                return false;
            }
            List<Integer> lengths = new ArrayList<>();
            for (int length = settings.minLength(); length <= settings.maxLength(); length++) {
                int target = ladder ? index + length : index - length;
                if (target < 0 || target > n - 1) {
                    continue;
                }
                RoomKind kind = kinds[target];
                if (kind == RoomKind.NORMAL || kind == RoomKind.BREAK) {
                    lengths.add(length);
                }
            }
            if (lengths.isEmpty()) {
                return false;
            }
            int length = lengths.get(random.nextInt(lengths.size()));
            ChallengeType challenge = random.nextDouble() < settings.mazeWeight()
                    ? ChallengeType.MAZE
                    : ChallengeType.WAVES;
            kinds[index] = ladder ? RoomKind.LADDER : RoomKind.CHUTE;
            transits[index] = new Transit(ladder ? length : -length, challenge);
            destination[ladder ? index + length : index - length] = true;
            return true;
        }

        /** Length of the run of consecutive CHUTE rooms that would contain {@code index} if it became one. */
        private int chuteRunWith(int index) {
            int run = 1;
            for (int i = index - 1; i >= 0 && kinds[i] == RoomKind.CHUTE; i--) {
                run++;
            }
            for (int i = index + 1; i < n && kinds[i] == RoomKind.CHUTE; i++) {
                run++;
            }
            return run;
        }
    }
}
