package mc.jonomore.chutes.game;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Exit button clicks, rejoin/quit handling and protection of players in a game. Every handler
 * is a no-op for players who are not in a game.
 */
final class GameListener implements Listener {

    private final GameManager manager;

    GameListener(GameManager manager) {
        this.manager = manager;
    }

    // ---------------------------------------------------------------- exit buttons

    /** Right click. Paper raises this once per hand, plus the "at" variant below; the manager debounces. */
    @EventHandler(priority = EventPriority.LOW)
    void onInteractEntity(PlayerInteractEntityEvent event) {
        handleEntityUse(event);
    }

    @EventHandler(priority = EventPriority.LOW)
    void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        handleEntityUse(event);
    }

    private void handleEntityUse(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (event.getRightClicked() instanceof Interaction interaction && manager.clickButton(player, interaction)) {
            event.setCancelled(true);
        } else if (manager.isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * Left click. Interaction entities cannot really be attacked, so Paper fires this already
     * cancelled (willAttack false); it is still the earliest signal of the click.
     */
    @EventHandler(priority = EventPriority.LOW)
    void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (event.getAttacked() instanceof Interaction interaction) {
            manager.clickButton(event.getPlayer(), interaction);
        }
    }

    /** Left click as a damage event (Interaction records attacks through it), and PvP protection. */
    @EventHandler(priority = EventPriority.LOW)
    void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (event.getEntity() instanceof Interaction interaction && manager.clickButton(player, interaction)) {
            event.setCancelled(true);
        } else if (manager.isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- protection

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !manager.isShielded(player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            manager.rescue(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && manager.isShielded(player)
                && event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onDrop(PlayerDropItemEvent event) {
        if (manager.isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && manager.isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onBreak(BlockBreakEvent event) {
        if (manager.isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onPlace(BlockPlaceEvent event) {
        if (manager.isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onInventoryOpen(InventoryOpenEvent event) {
        InventoryType type = event.getInventory().getType();
        if (event.getPlayer() instanceof Player player && type != InventoryType.PLAYER && type != InventoryType.CRAFTING
                && manager.isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() == Action.PHYSICAL && block != null && block.getType() == Material.FARMLAND
                && manager.isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player && manager.isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    void onDeath(PlayerDeathEvent event) {
        if (manager.isRestricted(event.getPlayer())) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    void onRespawn(PlayerRespawnEvent event) {
        Location home = manager.homeOf(event.getPlayer());
        if (home != null) {
            event.setRespawnLocation(home);
        }
    }

    // ---------------------------------------------------------------- connections

    @EventHandler(priority = EventPriority.MONITOR)
    void onJoin(PlayerJoinEvent event) {
        manager.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onQuit(PlayerQuitEvent event) {
        manager.onQuit(event.getPlayer());
    }
}
