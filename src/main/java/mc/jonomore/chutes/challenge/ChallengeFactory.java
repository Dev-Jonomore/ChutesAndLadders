package mc.jonomore.chutes.challenge;

import mc.jonomore.chutes.board.ChallengeType;

public interface ChallengeFactory {

    ChallengeType type();

    Challenge create(ChallengeContext context);
}
