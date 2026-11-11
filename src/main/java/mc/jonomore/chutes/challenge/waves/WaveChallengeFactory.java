package mc.jonomore.chutes.challenge.waves;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import mc.jonomore.chutes.board.ChallengeType;
import mc.jonomore.chutes.challenge.Challenge;
import mc.jonomore.chutes.challenge.ChallengeContext;
import mc.jonomore.chutes.challenge.ChallengeFactory;
import org.bukkit.entity.Player;

/**
 * Creates {@link ChallengeType#WAVES} challenges: survive waves of mobs in an arena.
 *
 * <p>Wave participants must be damageable and get hungry, unlike everywhere else in a game.
 * The game layer's invulnerability / hunger listeners must skip any player for which
 * {@link #isInWaveChallenge(Player)} is true. The wave challenge itself handles lethal damage,
 * deaths, friendly fire and damage to eliminated (spectating) participants.
 */
public final class WaveChallengeFactory implements ChallengeFactory {

    /** Participants (alive or eliminated) of every wave challenge currently running. Main thread only. */
    private static final Set<UUID> PARTICIPANTS = new HashSet<>();

    public WaveChallengeFactory() {
    }

    @Override
    public ChallengeType type() {
        return ChallengeType.WAVES;
    }

    @Override
    public Challenge create(ChallengeContext context) {
        return new WaveChallenge(context);
    }

    /**
     * Whether the player is a participant of a running wave challenge, from its {@code start()}
     * until it finishes or is aborted. Such players must not be made invulnerable or hunger-free.
     */
    public static boolean isInWaveChallenge(Player player) {
        return isInWaveChallenge(player.getUniqueId());
    }

    public static boolean isInWaveChallenge(UUID playerId) {
        return PARTICIPANTS.contains(playerId);
    }

    static void register(UUID playerId) {
        PARTICIPANTS.add(playerId);
    }

    static void unregister(UUID playerId) {
        PARTICIPANTS.remove(playerId);
    }
}
