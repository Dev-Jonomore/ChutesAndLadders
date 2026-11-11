package mc.jonomore.chutes.game;

import static net.kyori.adventure.text.Component.text;

import mc.jonomore.chutes.challenge.Challenge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/** The action bar shown to everyone in a running game. */
final class GameHud {

    private static final Component DOT = text(" · ", NamedTextColor.DARK_GRAY);

    private GameHud() {
    }

    static void show(GameSession session, Pawn pawn, long now) {
        Component line = line(session, pawn, now);
        for (Player player : pawn.onlineMembers()) {
            player.sendActionBar(line);
        }
    }

    static Component line(GameSession session, Pawn pawn, long now) {
        int size = session.board().size();
        if (session.state == SessionState.ENDING) {
            return text("Game over", NamedTextColor.GOLD);
        }
        if (pawn.finished) {
            return text("Finished #" + pawn.placement, NamedTextColor.GOLD)
                    .append(DOT).append(text(pawn.plies + " plies", NamedTextColor.WHITE))
                    .append(DOT).append(text(MovementRules.clock(pawn.finishTicks), NamedTextColor.WHITE));
        }
        Component line = text("Room " + (pawn.roomIndex + 1) + "/" + size, NamedTextColor.YELLOW)
                .append(DOT).append(text(pawn.plies + " plies", NamedTextColor.WHITE))
                .append(DOT).append(text(MovementRules.clock(now - session.startTick), NamedTextColor.WHITE));
        ActiveTransit transit = pawn.transit;
        if (transit != null) {
            Challenge challenge = transit.challenge;
            NamedTextColor color = transit.transit.isLadder() ? NamedTextColor.AQUA : NamedTextColor.RED;
            Component status = text(GameText.transitLabel(transit.transit), color);
            if (challenge != null) {
                try {
                    int percent = (int) Math.round(Math.clamp(challenge.progress(), 0.0, 1.0) * 100);
                    status = status.append(text(" " + percent + "%", color))
                            .append(DOT)
                            .append(text(MovementRules.clock(challenge.remainingTicks()) + " left", NamedTextColor.WHITE));
                } catch (RuntimeException ignored) {
                    // A misbehaving challenge must not break the HUD of every other game.
                }
            }
            line = line.append(DOT).append(status);
        }
        return line;
    }
}
