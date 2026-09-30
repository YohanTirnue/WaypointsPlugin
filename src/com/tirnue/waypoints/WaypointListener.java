package com.tirnue.waypoints;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;

public class WaypointListener implements Listener {
    private final TirnueWaypoints plugin;

    public WaypointListener(TirnueWaypoints plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Entity entity = event.getRightClicked();
        if (plugin.getWaypointRenderer().isWaypointInteraction(entity)) {
            UUID wpId = plugin.getWaypointRenderer().getWaypointIdFromInteraction(entity);
            if (wpId != null) {
                Waypoint wp = plugin.getWaypointManager().getWaypoint(wpId);
                if (wp != null) {
                    Player player = event.getPlayer();
                    if (wp.isOwner(player.getUniqueId())) {
                        plugin.getWaypointGUI().openManagementMenu(player, wp);
                    } else if (wp.isTrusted(player.getUniqueId()) || wp.isGlobal()) {
                        plugin.getWaypointGUI().openTravelMenu(player, wp);
                    } else {
                        player.sendMessage(plugin.getConfigManager().getMessage("no_permission"));
                    }
                }
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().distanceSquared(event.getTo()) > 0.01) {
            Player player = event.getPlayer();
            if (plugin.getTeleportManager().isCurrentlyTeleporting(player.getUniqueId())) {
                plugin.getTeleportManager().cancelTeleport(player.getUniqueId(), "You moved!");
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (plugin.getTeleportManager().isCurrentlyTeleporting(player.getUniqueId())) {
                plugin.getTeleportManager().cancelTeleport(player.getUniqueId(), "You took damage!");
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getTeleportManager().cancelTeleport(event.getPlayer().getUniqueId(), "Disconnected");
        // GUI cleanup handled by close event typically, but safe here too
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        plugin.getWaypointGUI().handleClick(event);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        plugin.getWaypointGUI().handleClose(event);
    }

    @EventHandler
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (plugin.getWaypointGUI().hasPendingRename(player.getUniqueId())) {
            event.setCancelled(true);
            String newName = PlainTextComponentSerializer.plainText().serialize(event.message());
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getWaypointGUI().processRename(player, newName);
            });
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()) return;
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.AIR && plugin.getLinkManager().isLedger(hand)) {
            Waypoint wp = plugin.getWaypointManager().getWaypointNear(player.getLocation(), 5.0);
            if (wp != null && wp.isOwner(player.getUniqueId())) {
                if (plugin.getLinkManager().redeemLedger(player, wp, hand)) {
                    player.sendMessage("§aLink established!");
                    hand.setAmount(hand.getAmount() - 1);
                }
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Waypoint wp : plugin.getWaypointManager().getAllWaypoints()) {
            if (wp.toBukkitLocation().getChunk().equals(event.getChunk())) {
                plugin.getWaypointRenderer().spawnWaypoint(wp);
            }
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Waypoint wp : plugin.getWaypointManager().getAllWaypoints()) {
            if (wp.toBukkitLocation().getChunk().equals(event.getChunk())) {
                plugin.getWaypointRenderer().despawnWaypoint(wp.getId());
            }
        }
    }
}
