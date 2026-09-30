package com.tirnue.waypoints;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
                        player.sendMessage(plugin.getConfigManager().getPrefix() + plugin.getConfigManager().getMessage("no-permission"));
                    }
                }
            }
        }
    }

    // HIGH fix: Check if player is teleporting FIRST before doing any math
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        // Early exit — skip all math if player isn't teleporting (vast majority of calls)
        if (!plugin.getTeleportManager().isCurrentlyTeleporting(player.getUniqueId())) return;

        // Only cancel if they actually moved position (not just head rotation)
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            plugin.getTeleportManager().cancelTeleport(player.getUniqueId(),
                    plugin.getConfigManager().getPrefix() + plugin.getConfigManager().getMessage("teleport-cancelled-move"));
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (plugin.getTeleportManager().isCurrentlyTeleporting(player.getUniqueId())) {
            plugin.getTeleportManager().cancelTeleport(player.getUniqueId(),
                    plugin.getConfigManager().getPrefix() + plugin.getConfigManager().getMessage("teleport-cancelled-damage"));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID pid = event.getPlayer().getUniqueId();
        // Cancel active teleports
        plugin.getTeleportManager().cancelTeleport(pid, "offline");
        // Cleanup cooldown entry to prevent memory leak
        plugin.getTeleportManager().clearCooldown(pid);
        // Cleanup pending GUI renames
        plugin.getWaypointGUI().cleanupPlayer(pid);
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
                    player.sendMessage(plugin.getConfigManager().getPrefix() + plugin.getConfigManager().getMessage("link-established"));
                }
            }
        }
    }

    // CRITICAL fix: Use math-based chunk coordinate comparison instead of Location.getChunk()
    // Location.getChunk() forces chunk loading which causes cascading lag
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        String worldName = chunk.getWorld().getName();

        for (Waypoint wp : plugin.getWaypointManager().getAllWaypoints()) {
            if (!wp.getWorldName().equals(worldName)) continue;
            int wpCx = ((int) wp.getX()) >> 4;
            int wpCz = ((int) wp.getZ()) >> 4;
            if (wpCx == cx && wpCz == cz) {
                if (!plugin.getWaypointRenderer().isSpawned(wp.getId())) {
                    plugin.getWaypointRenderer().spawnWaypoint(wp);
                }
            }
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        String worldName = chunk.getWorld().getName();

        for (Waypoint wp : plugin.getWaypointManager().getAllWaypoints()) {
            if (!wp.getWorldName().equals(worldName)) continue;
            int wpCx = ((int) wp.getX()) >> 4;
            int wpCz = ((int) wp.getZ()) >> 4;
            if (wpCx == cx && wpCz == cz) {
                plugin.getWaypointRenderer().despawnWaypoint(wp.getId());
            }
        }
    }
}
