package mc.jonomore.chutes.challenge.maze;

import mc.jonomore.chutes.board.ChallengeType;
import mc.jonomore.chutes.challenge.Challenge;
import mc.jonomore.chutes.challenge.ChallengeContext;
import mc.jonomore.chutes.challenge.ChallengeFactory;

public final class MazeChallengeFactory implements ChallengeFactory {

    public MazeChallengeFactory() {
    }

    @Override
    public ChallengeType type() {
        return ChallengeType.MAZE;
    }

    @Override
    public Challenge create(ChallengeContext context) {
        return new MazeChallenge(context);
    }
}
