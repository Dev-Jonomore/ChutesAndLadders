package mc.jonomore.chutes.board;

import mc.jonomore.chutes.ChutesConfig;

public interface BoardGenerator {

    /** Deterministic for a given settings + rooms + seed. See docs/DESIGN.md section 2. */
    Board generate(ChutesConfig.BoardSettings settings, int rooms, long seed);
}
