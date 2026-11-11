package mc.jonomore.chutes.challenge;

/**
 * @param completed    the challenge was fully beaten
 * @param progress     0..1, how far the participants got (1 when completed)
 * @param elapsedTicks ticks from start to end
 * @param parTicks     the time limit / par the elapsed time is measured against, positive
 */
public record ChallengeResult(boolean completed, double progress, long elapsedTicks, long parTicks) {

    public ChallengeResult {
        progress = Math.clamp(progress, 0.0, 1.0);
        if (parTicks <= 0) {
            throw new IllegalArgumentException("parTicks must be positive");
        }
    }
}
