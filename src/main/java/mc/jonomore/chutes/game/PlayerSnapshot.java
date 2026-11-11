package mc.jonomore.chutes.game;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/** Everything about a player the game changes, captured on entering a game and put back on leaving. */
final class PlayerSnapshot {

    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final GameMode gameMode;
    /** Full player inventory: storage, armor and offhand. */
    private final ItemStack[] contents;
    private final double health;
    private final int food;
    private final float saturation;
    private final float exhaustion;
    private final int level;
    private final float exp;
    private final int totalExperience;
    private final List<PotionEffect> effects;
    private final boolean allowFlight;
    private final boolean flying;

    private PlayerSnapshot(String world, double x, double y, double z, float yaw, float pitch, GameMode gameMode,
            ItemStack[] contents, double health, int food, float saturation, float exhaustion, int level, float exp,
            int totalExperience, List<PotionEffect> effects, boolean allowFlight, boolean flying) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.gameMode = gameMode;
        this.contents = contents;
        this.health = health;
        this.food = food;
        this.saturation = saturation;
        this.exhaustion = exhaustion;
        this.level = level;
        this.exp = exp;
        this.totalExperience = totalExperience;
        this.effects = List.copyOf(effects);
        this.allowFlight = allowFlight;
        this.flying = flying;
    }

    static PlayerSnapshot capture(Player player) {
        Location location = player.getLocation();
        ItemStack[] contents = player.getInventory().getContents();
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return new PlayerSnapshot(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), player.getGameMode(), copy, player.getHealth(),
                player.getFoodLevel(), player.getSaturation(), player.getExhaustion(), player.getLevel(),
                player.getExp(), player.getTotalExperience(), new ArrayList<>(player.getActivePotionEffects()),
                player.getAllowFlight(), player.isFlying());
    }

    void apply(Player player) {
        player.setGameMode(gameMode);
        player.teleport(location());
        player.setFallDistance(0);
        player.setFireTicks(0);

        player.getInventory().setContents(contents);
        player.setItemOnCursor(null);

        double max = maxHealth(player);
        player.setHealth(health <= 0 ? max : Math.min(health, max));
        player.setFoodLevel(food);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);
        player.setTotalExperience(totalExperience);
        player.setLevel(level);
        player.setExp(exp);

        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.addPotionEffects(effects);

        player.setAllowFlight(allowFlight);
        player.setFlying(allowFlight && flying);
    }

    private Location location() {
        World target = Bukkit.getWorld(world);
        if (target == null) {
            return Bukkit.getWorlds().getFirst().getSpawnLocation();
        }
        return new Location(target, x, y, z, yaw, pitch);
    }

    static double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }

    YamlConfiguration toYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("world", world);
        yaml.set("x", x);
        yaml.set("y", y);
        yaml.set("z", z);
        yaml.set("yaw", (double) yaw);
        yaml.set("pitch", (double) pitch);
        yaml.set("game-mode", gameMode.name());
        yaml.set("inventory", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(contents)));
        yaml.set("health", health);
        yaml.set("food", food);
        yaml.set("saturation", (double) saturation);
        yaml.set("exhaustion", (double) exhaustion);
        yaml.set("level", level);
        yaml.set("exp", (double) exp);
        yaml.set("total-experience", totalExperience);
        yaml.set("effects", effects);
        yaml.set("allow-flight", allowFlight);
        yaml.set("flying", flying);
        return yaml;
    }

    static PlayerSnapshot fromYaml(YamlConfiguration yaml) {
        String encoded = yaml.getString("inventory", "");
        ItemStack[] contents = encoded.isEmpty()
                ? new ItemStack[0]
                : ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded));
        List<PotionEffect> effects = new ArrayList<>();
        for (Object entry : yaml.getList("effects", List.of())) {
            if (entry instanceof PotionEffect effect) {
                effects.add(effect);
            }
        }
        GameMode mode;
        try {
            mode = GameMode.valueOf(yaml.getString("game-mode", "SURVIVAL").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            mode = GameMode.SURVIVAL;
        }
        return new PlayerSnapshot(yaml.getString("world", ""), yaml.getDouble("x"), yaml.getDouble("y"),
                yaml.getDouble("z"), (float) yaml.getDouble("yaw"), (float) yaml.getDouble("pitch"), mode, contents,
                yaml.getDouble("health", 20), yaml.getInt("food", 20), (float) yaml.getDouble("saturation", 5),
                (float) yaml.getDouble("exhaustion"), yaml.getInt("level"), (float) yaml.getDouble("exp"),
                yaml.getInt("total-experience"), effects, yaml.getBoolean("allow-flight"), yaml.getBoolean("flying"));
    }
}
