package mc.jonomore.chutes.game;

import static net.kyori.adventure.text.Component.text;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import mc.jonomore.chutes.board.Transit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * The short animations between pressing an exit and leaving the room. Each one is a task that
 * runs every tick, plays to whoever the viewer supplier returns at that moment, and calls
 * {@code done} once at the end. Cancelling the task stops it without calling {@code done}.
 */
final class ExitAnimations {

    /** Ticks at which the rolling die shows a new random face; the gaps widen so it slows down. */
    private static final int[] ROLL_FRAMES = {0, 2, 4, 7, 10, 14, 19};
    private static final int ROLL_LAND_TICK = 25;
    private static final int ROLL_END_TICK = 40;

    private static final int DISCOVERY_TICKS = 44;

    private static final Title.Times FLICKER = Title.Times.times(Duration.ZERO, Duration.ofMillis(700), Duration.ZERO);
    private static final Title.Times HOLD = Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(300));

    private ExitAnimations() {
    }

    /** Flickers through random faces, slowing down, then lands on {@code result}. */
    static BukkitTask roll(Plugin plugin, Supplier<List<Player>> viewers, Random random, int maxRoll, int result,
            String roller, Runnable done) {
        Component subtitle = text(roller + " is rolling...", NamedTextColor.GRAY);
        return new BukkitRunnable() {
            private int tick;
            private int frame;
            private int shown;

            @Override
            public void run() {
                if (frame < ROLL_FRAMES.length && tick == ROLL_FRAMES[frame]) {
                    shown = differentFace(random, maxRoll, shown);
                    Title title = Title.title(text(shown, NamedTextColor.WHITE, TextDecoration.BOLD), subtitle, FLICKER);
                    // The tick sound drops in pitch as the die slows.
                    float pitch = 1.8f - 0.12f * frame;
                    for (Player player : viewers.get()) {
                        player.showTitle(title);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, pitch);
                    }
                    frame++;
                } else if (tick == ROLL_LAND_TICK) {
                    Title title = Title.title(text(result, NamedTextColor.GOLD, TextDecoration.BOLD),
                            text(roller + " rolled " + result, NamedTextColor.YELLOW), HOLD);
                    for (Player player : viewers.get()) {
                        player.showTitle(title);
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.2f);
                    }
                } else if (tick >= ROLL_END_TICK) {
                    cancel();
                    done.run();
                    return;
                }
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** A face other than the one showing, so every flicker visibly changes (when there is a choice). */
    private static int differentFace(Random random, int maxRoll, int current) {
        if (maxRoll <= 1) {
            return 1;
        }
        int face = 1 + random.nextInt(maxRoll - 1);
        return face >= current && current > 0 ? face + 1 : face;
    }

    /** Ladder or chute, by the sign of the transit. */
    static BukkitTask discovery(Plugin plugin, Supplier<List<Player>> viewers, Transit transit, Runnable done) {
        return transit.isLadder() ? ladder(plugin, viewers, transit, done) : chute(plugin, viewers, transit, done);
    }

    /**
     * Ladder: players lift off the floor inside a rising double helix of light while a chime
     * climbs the scale.
     */
    private static BukkitTask ladder(Plugin plugin, Supplier<List<Player>> viewers, Transit transit, Runnable done) {
        Title title = Title.title(text("LADDER!", NamedTextColor.AQUA, TextDecoration.BOLD),
                text("Up to +" + transit.length() + " rooms", NamedTextColor.GREEN), HOLD);
        return new BukkitRunnable() {
            private int tick;

            @Override
            public void run() {
                List<Player> players = viewers.get();
                if (tick == 0) {
                    for (Player player : players) {
                        player.showTitle(title);
                        player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, DISCOVERY_TICKS, 1, false, false, false));
                    }
                }
                if (tick >= DISCOVERY_TICKS) {
                    cancel();
                    for (Player player : players) {
                        player.removePotionEffect(PotionEffectType.LEVITATION);
                        player.setFallDistance(0);
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
                    }
                    done.run();
                    return;
                }
                double progress = tick / (double) DISCOVERY_TICKS;
                for (Player centre : players) {
                    Location base = centre.getLocation();
                    // Two strands opposite each other, winding upwards around the player.
                    double angle = tick * 0.45;
                    double height = progress * 3.0;
                    for (int strand = 0; strand < 2; strand++) {
                        double a = angle + strand * Math.PI;
                        Location point = base.clone().add(Math.cos(a) * 0.9, height, Math.sin(a) * 0.9);
                        for (Player viewer : players) {
                            viewer.spawnParticle(Particle.END_ROD, point, 2, 0.02, 0.02, 0.02, 0.01);
                        }
                    }
                }
                if (tick % 4 == 0) {
                    float pitch = (float) (0.6 + 1.4 * progress);
                    for (Player player : players) {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, pitch);
                    }
                }
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * Chute: the lights go out, a trapdoor bangs, and smoke is sucked down into the floor in a
     * tightening spiral while a bass note slides down the scale.
     */
    private static BukkitTask chute(Plugin plugin, Supplier<List<Player>> viewers, Transit transit, Runnable done) {
        Title title = Title.title(text("CHUTE!", NamedTextColor.RED, TextDecoration.BOLD),
                text("Up to -" + transit.length() + " rooms", NamedTextColor.GOLD), HOLD);
        return new BukkitRunnable() {
            private int tick;

            @Override
            public void run() {
                List<Player> players = viewers.get();
                if (tick == 0) {
                    for (Player player : players) {
                        player.showTitle(title);
                        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, DISCOVERY_TICKS + 20, 0, false, false, false));
                        player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1f, 0.6f);
                    }
                }
                if (tick >= DISCOVERY_TICKS) {
                    cancel();
                    for (Player player : players) {
                        player.removePotionEffect(PotionEffectType.DARKNESS);
                        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 0.6f);
                    }
                    done.run();
                    return;
                }
                double progress = tick / (double) DISCOVERY_TICKS;
                for (Player centre : players) {
                    Location base = centre.getLocation();
                    // A ring that starts wide at head height and closes in on the player's feet.
                    double radius = 2.2 * (1 - progress) + 0.2;
                    double height = 2.0 * (1 - progress);
                    for (int i = 0; i < 4; i++) {
                        double a = tick * 0.6 + i * Math.PI / 2;
                        Location point = base.clone().add(Math.cos(a) * radius, height, Math.sin(a) * radius);
                        for (Player viewer : players) {
                            viewer.spawnParticle(Particle.LARGE_SMOKE, point, 1, 0, 0, 0, 0);
                        }
                    }
                }
                if (tick % 4 == 0) {
                    float pitch = (float) (1.6 - 1.1 * progress);
                    for (Player player : players) {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, pitch);
                    }
                }
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
