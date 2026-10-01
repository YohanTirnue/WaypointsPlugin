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
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack hand = event.getItemInHand();
        
        // Check if the placed item is a Waypoint Core (has PDC marker)
        if (hand.hasItemMeta() && hand.getItemMeta().getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey(plugin, "tirnue_wp_core"), org.bukkit.persistence.PersistentDataType.BYTE)) {
            
            // Cancel the block placement - don't place lodestone in world
            event.setCancelled(true);
            
            // Check waypoint limit
            int count = plugin.getWaypointManager().getWaypointCount(player.getUniqueId());
            int limit = player.hasPermission("waypoint.vip") ? plugin.getConfigManager().getVipMaxWaypoints() : plugin.getConfigManager().getDefaultMaxWaypoints();
            if (count >= limit) {
                player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', plugin.getConfigManager().getPrefix() + " &cMax waypoint limit reached (" + limit + ")."));
                return;
            }
            
            // Consume one core from hand
            if (event.getHand() == EquipmentSlot.HAND) {
                ItemStack main = player.getInventory().getItemInMainHand();
                main.setAmount(main.getAmount() - 1);
                player.getInventory().setItemInMainHand(main);
            } else {
                ItemStack off = player.getInventory().getItemInOffHand();
                off.setAmount(off.getAmount() - 1);
                player.getInventory().setItemInOffHand(off);
            }
            
            // Create waypoint with temporary name at block location
            org.bukkit.Location loc = event.getBlock().getLocation().add(0.5, 0, 0.5); // center of block
            Waypoint wp = plugin.getWaypointManager().createWaypoint(player, "Unnamed Waypoint", loc);
            
            // Store pending naming and prompt player
            plugin.getWaypointGUI().startPendingCreate(player.getUniqueId(), wp.getId());
            player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', 
                plugin.getConfigManager().getPrefix() + " &a✦ Waypoint placed! &fType a name in chat &7(or 'cancel'):"));
            
            // Play a nice sound
            player.playSound(loc, org.bukkit.Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.5f);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("decay.notify-on-login", true)) return;
        Player player = event.getPlayer();
        // Delay 3 seconds so it's not lost in join spam
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            java.util.List<Waypoint> decaying = new java.util.ArrayList<>();
            for (Waypoint wp : plugin.getWaypointManager().getWaypointsByOwner(player.getUniqueId())) {
                if (wp.isDecaying()) {
                    wp.setDecaying(false);
                    wp.setDecayStartTime(0);
                    decaying.add(wp);
                }
            }
            if (!decaying.isEmpty()) {
                player.sendMessage(plugin.getConfigManager().getPrefix() + org.bukkit.ChatColor.translateAlternateColorCodes('&', "&aWelcome back! Your " + decaying.size() + " waypoint(s) are no longer decaying."));
                plugin.getWaypointManager().saveAsync();
            }
        }, 60L);
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
        // Handle new waypoint naming
        if (plugin.getWaypointGUI().hasPendingCreate(player.getUniqueId())) {
            event.setCancelled(true);
            String input = PlainTextComponentSerializer.plainText().serialize(event.message());
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getWaypointGUI().processCreate(player, input);
            });
            return;
        }
        // Handle trust addition input
        if (plugin.getWaypointGUI().hasPendingTrust(player.getUniqueId())) {
            event.setCancelled(true);
            String input = PlainTextComponentSerializer.plainText().serialize(event.message());
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getWaypointGUI().processTrust(player, input);
            });
            return;
        }
        // Handle fee change input
        if (plugin.getWaypointGUI().hasPendingFeeChange(player.getUniqueId())) {
            event.setCancelled(true);
            String input = PlainTextComponentSerializer.plainText().serialize(event.message());
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getWaypointGUI().processFeeChange(player, input);
            });
            return;
        }
        // Handle rename input
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
        if (hand.getType() == Material.AIR) {
            hand = player.getInventory().getItemInOffHand();
        }
        if (hand.getType() == Material.AIR) return;

        // Check Guest Pass redemption anywhere!
        if (plugin.getLinkManager().isGuestPass(hand)) {
            event.setCancelled(true);
            plugin.getLinkManager().redeemGuestPass(player, hand);
            return;
        }

        // Check Ledger linking near a waypoint
        if (plugin.getLinkManager().isLedger(hand)) {
            Waypoint wp = plugin.getWaypointManager().getWaypointNear(player.getLocation(), 5.0);
            if (wp != null && wp.isOwner(player.getUniqueId())) {
                event.setCancelled(true);
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
