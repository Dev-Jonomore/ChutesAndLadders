package mc.jonomore.chutes.game;

import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.meta.FireworkMeta;

/** The celebration spot at board index N: a walled platform finished pawns wait on. */
final class FinishPlatform {

    static final int SIZE = 11;
    static final int HEIGHT = 5;
    private static final int WALL = 3;

    private FinishPlatform() {
    }

    static void place(Location origin) {
        World world = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        int center = SIZE / 2;
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                boolean edge = x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
                boolean corner = (x == 0 || x == SIZE - 1) && (z == 0 || z == SIZE - 1);
                boolean middle = Math.abs(x - center) <= 1 && Math.abs(z - center) <= 1;
                Material floor = corner ? Material.SEA_LANTERN : middle ? Material.GOLD_BLOCK : Material.QUARTZ_BLOCK;
                world.getBlockAt(ox + x, oy, oz + z).setType(floor, false);
                if (edge) {
                    for (int y = 1; y <= WALL; y++) {
                        world.getBlockAt(ox + x, oy + y, oz + z).setType(Material.GLASS, false);
                    }
                }
            }
        }
    }

    static void clear(Location origin) {
        World world = origin.getWorld();
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                for (int z = 0; z < SIZE; z++) {
                    world.getBlockAt(origin.getBlockX() + x, origin.getBlockY() + y, origin.getBlockZ() + z)
                            .setType(Material.AIR, false);
                }
            }
        }
    }

    static Location spawn(Location origin) {
        Location spawn = origin.clone().add(SIZE / 2.0, 1, SIZE / 2.0);
        spawn.setYaw(0);
        spawn.setPitch(0);
        return spawn;
    }

    static void celebrate(Location origin) {
        World world = origin.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 3; i++) {
            Location at = origin.clone().add(2 + random.nextInt(SIZE - 4) + 0.5, 1, 2 + random.nextInt(SIZE - 4) + 0.5);
            Color color = Color.fromRGB(random.nextInt(0x1000000));
            world.spawn(at, Firework.class, firework -> {
                firework.setPersistent(false);
                FireworkMeta meta = firework.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder()
                        .with(FireworkEffect.Type.BALL_LARGE)
                        .withColor(color)
                        .withFade(Color.WHITE)
                        .flicker(true)
                        .trail(true)
                        .build());
                meta.setPower(1);
                firework.setFireworkMeta(meta);
            });
        }
    }
}
