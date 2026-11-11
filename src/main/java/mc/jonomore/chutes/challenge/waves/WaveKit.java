package mc.jonomore.chutes.challenge.waves;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

/** The fixed fighting kit and the player state resets around it. */
final class WaveKit {

    private WaveKit() {
    }

    /** Clears the player, then hands out the kit in adventure mode. */
    static void equip(Player player) {
        reset(player);
        player.setGameMode(GameMode.ADVENTURE);
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(0, ItemStack.of(Material.IRON_SWORD));
        inventory.setItem(1, ItemStack.of(Material.BOW));
        inventory.setItem(2, ItemStack.of(Material.COOKED_BEEF, 16));
        inventory.setItem(8, ItemStack.of(Material.ARROW, 64));
        inventory.setItemInOffHand(ItemStack.of(Material.SHIELD));
        inventory.setHelmet(ItemStack.of(Material.IRON_HELMET));
        inventory.setChestplate(ItemStack.of(Material.IRON_CHESTPLATE));
        inventory.setLeggings(ItemStack.of(Material.IRON_LEGGINGS));
        inventory.setBoots(ItemStack.of(Material.IRON_BOOTS));
        inventory.setHeldItemSlot(0);
    }

    /** Empty inventory, full health and food, no fire, no effects. Leaves the game mode alone. */
    static void reset(Player player) {
        player.closeInventory();
        player.setItemOnCursor(null);
        player.getInventory().clear();
        heal(player);
        player.clearActivePotionEffects();
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0);
        player.setVelocity(new Vector());
    }

    static void heal(Player player) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth != null ? maxHealth.getValue() : 20.0);
        player.setFoodLevel(20);
        player.setSaturation(5.0f);
        player.setExhaustion(0.0f);
    }
}
