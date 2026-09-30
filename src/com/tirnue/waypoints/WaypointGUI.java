package com.tirnue.waypoints;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

public class WaypointGUI {
    private final TirnueWaypoints plugin;
    private final Map<UUID, GUISession> sessions = new HashMap<>();
    private final Map<UUID, UUID> pendingRenames = new HashMap<>();

    public enum GUIType { TRAVEL, MANAGEMENT, TRUST, LINKS, REDEEM_CONFIRM, ACTIVITY, CUSTOMIZE }

    public static class GUISession {
        public GUIType type;
        public UUID waypointId;
        public int page;

        public GUISession(GUIType type, UUID waypointId, int page) {
            this.type = type;
            this.waypointId = waypointId;
            this.page = page;
        }
    }

    public WaypointGUI(TirnueWaypoints plugin) {
        this.plugin = plugin;
    }

    private Component deserializeTitle(String title) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(title);
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            if (lore != null && lore.length > 0) {
                List<String> loreList = new ArrayList<>();
                for (String l : lore) {
                    loreList.add(ChatColor.translateAlternateColorCodes('&', l));
                }
                meta.setLore(loreList);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private String c(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public void openTravelMenu(Player player, Waypoint fromWaypoint) {
        Inventory inv = Bukkit.createInventory(null, 54, deserializeTitle("&b✦ Waypoint Travel"));
        
        ItemStack border = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++) inv.setItem(i, border);
        for (int i = 18; i < 27; i++) inv.setItem(i, border);

        int slot = 10;
        List<Waypoint> globalWaypoints = plugin.getWaypointManager().getGlobalWaypoints();
        for (Waypoint gWp : globalWaypoints) {
            if (slot > 16) break;
            if (gWp.getId().equals(fromWaypoint.getId())) continue;
            double dist = fromWaypoint.distanceTo(gWp);
            String distStr = dist == -1 ? "Different Dimension" : String.format("%.1f blocks", dist);
            double time = plugin.getTeleportManager().calculateWarmupSeconds(fromWaypoint, gWp);
            inv.setItem(slot++, createItem(Material.BEACON, "&6" + gWp.getName(), 
                "&7Distance: &f" + distStr, 
                "&7Warp Time: &f" + String.format("%.1fs", time)));
        }

        slot = 28;
        List<Waypoint> linked = new ArrayList<>();
        for (UUID lid : fromWaypoint.getLinkedWaypointIds()) {
            Waypoint linkedWp = plugin.getWaypointManager().getWaypoint(lid);
            if (linkedWp != null) linked.add(linkedWp);
        }

        UUID pId = player.getUniqueId();
        linked.sort((w1, w2) -> {
            boolean f1 = plugin.getWaypointManager().isFavorite(pId, w1.getId());
            boolean f2 = plugin.getWaypointManager().isFavorite(pId, w2.getId());
            if (f1 && !f2) return -1;
            if (!f1 && f2) return 1;
            return w1.getName().compareToIgnoreCase(w2.getName());
        });

        if (linked.isEmpty()) {
            inv.setItem(31, createItem(Material.GRAY_STAINED_GLASS, "&7No destinations available"));
        } else {
            for (Waypoint lWp : linked) {
                if (slot > 34 && slot < 37) slot = 37;
                if (slot > 43) break; // Needs pagination ideally, but following layout
                double dist = fromWaypoint.distanceTo(lWp);
                String distStr = dist == -1 ? "Different Dimension" : String.format("%.1f blocks", dist);
                double time = plugin.getTeleportManager().calculateWarmupSeconds(fromWaypoint, lWp);
                
                boolean isFav = plugin.getWaypointManager().isFavorite(pId, lWp.getId());
                String prefix = isFav ? "&6★ " : "&7☆ ";
                
                inv.setItem(slot++, createItem(Material.ENDER_EYE, prefix + "&d" + lWp.getName(),
                    "&7Owner: &f" + lWp.getOwnerName(),
                    "&7Distance: &f" + distStr,
                    "&7Warp Time: &f" + String.format("%.1fs", time),
                    "",
                    "&eClick to warp | &7Shift-click to toggle favorite"));
            }
        }

        inv.setItem(49, createItem(Material.BARRIER, "&cClose"));
        
        sessions.put(player.getUniqueId(), new GUISession(GUIType.TRAVEL, fromWaypoint.getId(), 1));
        player.openInventory(inv);
    }

    public void openManagementMenu(Player player, Waypoint wp) {
        Inventory inv = Bukkit.createInventory(null, 27, deserializeTitle("&6✦ " + wp.getName() + " - Management"));
        
        inv.setItem(10, createItem(Material.NAME_TAG, "&e🏷 Rename Waypoint", "&7Click to rename"));
        inv.setItem(11, createItem(Material.PLAYER_HEAD, "&a👥 Manage Trust", "&7" + wp.getTrustedPlayers().size() + " trusted players"));
        inv.setItem(12, createItem(Material.IRON_BARS, "&b\uD83D\uDD17 Connected Waypoints", "&7" + wp.getLinkedWaypointIds().size() + " active links"));
        inv.setItem(13, createItem(Material.BOOK, "&6📋 Activity Log", "&7View recent warp activity"));
        inv.setItem(14, createItem(Material.PAINTING, "&d🎨 Customize Appearance", "&7Change particles & crystal"));
        inv.setItem(15, createItem(Material.PAPER, "&e✦ Generate Guest Pass", "&7Create a single-use warp token"));
        inv.setItem(16, createItem(Material.WRITABLE_BOOK, "&d📜 Generate Link Ledger"));
        inv.setItem(17, createItem(Material.BOOK, "&a📥 Redeem Link Ledger", "&7Hold a ledger and click"));
        inv.setItem(26, createItem(Material.TNT, "&c💥 Destroy Waypoint", "&7Shift-click to confirm"));
        inv.setItem(22, createItem(Material.ARROW, "&7Close"));

        sessions.put(player.getUniqueId(), new GUISession(GUIType.MANAGEMENT, wp.getId(), 1));
        player.openInventory(inv);
    }

    public void openCustomizeMenu(Player player, Waypoint wp) {
        Inventory inv = Bukkit.createInventory(null, 27, deserializeTitle("&d✦ Customize - " + wp.getName()));
        
        Material[] particles = {Material.ENDER_PEARL, Material.BLAZE_POWDER, Material.AMETHYST_SHARD, Material.MAGMA_CREAM, Material.SOUL_LANTERN, Material.END_ROD, Material.RED_DYE, Material.HONEYCOMB, Material.CHERRY_LEAVES};
        String[] partNames = {"ENCHANT", "PORTAL", "REVERSE_PORTAL", "FLAME", "SOUL_FIRE_FLAME", "END_ROD", "HEART", "DRIPPING_HONEY", "CHERRY_LEAVES"};
        for(int i = 0; i < 9; i++) {
            ItemStack item = createItem(particles[i], "&a" + partNames[i], "&7Click to select");
            if (partNames[i].equals(wp.getParticleType())) {
                item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
                ItemMeta meta = item.getItemMeta();
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                item.setItemMeta(meta);
            }
            inv.setItem(i, item);
        }

        Material[] crystals = {Material.AMETHYST_SHARD, Material.DIAMOND, Material.EMERALD, Material.NETHER_STAR, Material.END_CRYSTAL, Material.ENDER_EYE, Material.HEART_OF_THE_SEA, Material.PRISMARINE_CRYSTALS, Material.GLOWSTONE_DUST};
        for(int i = 0; i < 9; i++) {
            ItemStack item = createItem(crystals[i], "&b" + crystals[i].name(), "&7Click to select");
            if (crystals[i].name().equals(wp.getCrystalMaterial())) {
                item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
                ItemMeta meta = item.getItemMeta();
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                item.setItemMeta(meta);
            }
            inv.setItem(i + 9, item);
        }
        
        inv.setItem(22, createItem(Material.ARROW, "&7Back"));
        
        sessions.put(player.getUniqueId(), new GUISession(GUIType.CUSTOMIZE, wp.getId(), 1));
        player.openInventory(inv);
    }

    public void openActivityMenu(Player player, Waypoint wp) {
        Inventory inv = Bukkit.createInventory(null, 54, deserializeTitle("&6✦ Activity - " + wp.getName()));
        
        List<Waypoint.ActivityEntry> log = wp.getActivityLog();
        if (log == null || log.isEmpty()) {
            inv.setItem(31, createItem(Material.GRAY_STAINED_GLASS_PANE, "&7No recent activity"));
        } else {
            int slot = 0;
            for (Waypoint.ActivityEntry entry : log) {
                if (slot >= 45) break;
                String desc = entry.getAction().equals("WARP_TO") ? "Warped here from " + entry.getDestinationName() : "Warped to " + entry.getDestinationName();
                inv.setItem(slot++, createItem(Material.CLOCK, "&f" + entry.getPlayerName(),
                    "&7" + desc,
                    "&8" + entry.getFormattedTime()));
            }
        }
        
        inv.setItem(49, createItem(Material.ARROW, "&7Back"));
        
        sessions.put(player.getUniqueId(), new GUISession(GUIType.ACTIVITY, wp.getId(), 1));
        player.openInventory(inv);
    }

    public void openTrustMenu(Player player, Waypoint wp) {
        Inventory inv = Bukkit.createInventory(null, 54, deserializeTitle("&a✦ Trust - " + wp.getName()));
        
        int slot = 0;
        for (UUID trustedId : wp.getTrustedPlayers()) {
            if (slot >= 45) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(trustedId);
            ItemStack head = createItem(Material.PLAYER_HEAD, "&f" + (op.getName() != null ? op.getName() : "Unknown"), "&cClick to remove");
            ItemMeta meta = head.getItemMeta();
            if (meta instanceof SkullMeta) {
                ((SkullMeta) meta).setOwningPlayer(op);
                head.setItemMeta(meta);
            }
            inv.setItem(slot++, head);
        }

        inv.setItem(49, createItem(Material.EMERALD, "&aAdd Trust", "&7Click to open chat prompt"));
        inv.setItem(53, createItem(Material.ARROW, "&7Back"));

        sessions.put(player.getUniqueId(), new GUISession(GUIType.TRUST, wp.getId(), 1));
        player.openInventory(inv);
    }

    public void openLinksMenu(Player player, Waypoint wp) {
        Inventory inv = Bukkit.createInventory(null, 54, deserializeTitle("&b✦ Links - " + wp.getName()));
        
        int slot = 0;
        for (UUID linkId : wp.getLinkedWaypointIds()) {
            if (slot >= 45) break;
            Waypoint lWp = plugin.getWaypointManager().getWaypoint(linkId);
            if (lWp != null) {
                double dist = wp.distanceTo(lWp);
                String distStr = dist == -1 ? "Different Dimension" : String.format("%.1f blocks", dist);
                inv.setItem(slot++, createItem(Material.ENDER_EYE, "&d" + lWp.getName(),
                    "&7Owner: &f" + lWp.getOwnerName(),
                    "&7Distance: &f" + distStr,
                    "&cClick to revoke/sever link"));
            }
        }

        inv.setItem(49, createItem(Material.ARROW, "&7Back"));
        
        sessions.put(player.getUniqueId(), new GUISession(GUIType.LINKS, wp.getId(), 1));
        player.openInventory(inv);
    }

    public void handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        GUISession session = sessions.get(player.getUniqueId());
        
        if (session == null) return;
        event.setCancelled(true);
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;
        
        Waypoint wp = plugin.getWaypointManager().getWaypoint(session.waypointId);
        if (wp == null) {
            player.closeInventory();
            return;
        }

        if (!event.getCurrentItem().hasItemMeta() || !event.getCurrentItem().getItemMeta().hasDisplayName()) return;
        String itemName = ChatColor.stripColor(event.getCurrentItem().getItemMeta().getDisplayName());

        switch (session.type) {
            case TRAVEL:
                if (event.getSlot() == 49) {
                    player.closeInventory();
                } else {
                    String wpName = itemName;
                    Waypoint dest = null;
                    if (event.getSlot() >= 10 && event.getSlot() <= 16) {
                        for (Waypoint gwp : plugin.getWaypointManager().getGlobalWaypoints()) {
                            if (gwp.getName().equals(wpName)) { dest = gwp; break; }
                        }
                    } else if ((event.getSlot() >= 28 && event.getSlot() <= 34) || (event.getSlot() >= 37 && event.getSlot() <= 43)) {
                        for (UUID lid : wp.getLinkedWaypointIds()) {
                            Waypoint lwp = plugin.getWaypointManager().getWaypoint(lid);
                            if (lwp != null && (wpName.equals("★ " + lwp.getName()) || wpName.equals("☆ " + lwp.getName()))) { dest = lwp; break; }
                        }
                    }
                    if (dest != null) {
                        if (event.isShiftClick()) {
                            plugin.getWaypointManager().toggleFavorite(player.getUniqueId(), dest.getId());
                            openTravelMenu(player, wp);
                        } else {
                            player.closeInventory();
                            plugin.getTeleportManager().startTeleport(player, wp, dest);
                        }
                    }
                }
                break;
            case MANAGEMENT:
                if (event.getSlot() == 10) { // Rename
                    player.closeInventory();
                    pendingRenames.put(player.getUniqueId(), wp.getId());
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aType the new name for your waypoint in chat, or type 'cancel' to abort."));
                } else if (event.getSlot() == 11) { // Manage Trust
                    openTrustMenu(player, wp);
                } else if (event.getSlot() == 12) { // Connected Links
                    openLinksMenu(player, wp);
                } else if (event.getSlot() == 13) { // Activity Log
                    openActivityMenu(player, wp);
                } else if (event.getSlot() == 14) { // Customize Appearance
                    openCustomizeMenu(player, wp);
                } else if (event.getSlot() == 15) { // Guest Pass
                    ItemStack pass = plugin.getLinkManager().generateGuestPass(wp);
                    player.getInventory().addItem(pass);
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aGuest pass generated."));
                } else if (event.getSlot() == 16) { // Generate Ledger
                    ItemStack ledger = plugin.getLinkManager().generateLedger(wp);
                    player.getInventory().addItem(ledger);
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aLink ledger generated."));
                } else if (event.getSlot() == 17) { // Redeem Ledger
                    ItemStack hand = player.getInventory().getItemInMainHand();
                    if (plugin.getLinkManager().isLedger(hand)) {
                        if (plugin.getLinkManager().redeemLedger(player, wp, hand)) {
                            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aSuccessfully linked waypoints!"));
                            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                            openManagementMenu(player, wp);
                        }
                    } else {
                        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cYou must be holding a Link Ledger to redeem it."));
                    }
                } else if (event.getSlot() == 26) { // Destroy
                    if (event.isShiftClick()) {
                        player.closeInventory();
                        plugin.getWaypointManager().deleteWaypoint(wp.getId());
                        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cWaypoint destroyed."));
                    } else {
                        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cShift-click to confirm destruction."));
                    }
                } else if (event.getSlot() == 22) { // Close
                    player.closeInventory();
                }
                break;
            case ACTIVITY:
                if (event.getSlot() == 49) {
                    openManagementMenu(player, wp);
                }
                break;
            case CUSTOMIZE:
                if (event.getSlot() == 22) {
                    openManagementMenu(player, wp);
                } else if (event.getSlot() >= 0 && event.getSlot() < 9) {
                    String[] partNames = {"ENCHANT", "PORTAL", "REVERSE_PORTAL", "FLAME", "SOUL_FIRE_FLAME", "END_ROD", "HEART", "DRIPPING_HONEY", "CHERRY_LEAVES"};
                    wp.setParticleType(partNames[event.getSlot()]);
                    plugin.getWaypointManager().saveAsync();
                    openCustomizeMenu(player, wp);
                } else if (event.getSlot() >= 9 && event.getSlot() < 18) {
                    Material[] crystals = {Material.AMETHYST_SHARD, Material.DIAMOND, Material.EMERALD, Material.NETHER_STAR, Material.END_CRYSTAL, Material.ENDER_EYE, Material.HEART_OF_THE_SEA, Material.PRISMARINE_CRYSTALS, Material.GLOWSTONE_DUST};
                    wp.setCrystalMaterial(crystals[event.getSlot() - 9].name());
                    plugin.getWaypointManager().saveAsync();
                    plugin.getWaypointRenderer().despawnWaypoint(wp.getId());
                    plugin.getWaypointRenderer().spawnWaypoint(wp);
                    openCustomizeMenu(player, wp);
                }
                break;
            case TRUST:
                if (event.getSlot() == 49) { // Add trust
                    player.closeInventory();
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aUse &e/wp trust <player>&a to add a player."));
                } else if (event.getSlot() == 53) { // Back
                    openManagementMenu(player, wp);
                } else if (event.getCurrentItem().getType() == Material.PLAYER_HEAD) {
                    ItemMeta meta = event.getCurrentItem().getItemMeta();
                    if (meta instanceof SkullMeta) {
                        OfflinePlayer op = ((SkullMeta) meta).getOwningPlayer();
                        if (op != null) {
                            plugin.getWaypointManager().untrustPlayer(wp.getId(), op.getUniqueId());
                            openTrustMenu(player, wp);
                        }
                    }
                }
                break;
            case LINKS:
                if (event.getSlot() == 49) { // Back
                    openManagementMenu(player, wp);
                } else if (event.getCurrentItem().getType() == Material.ENDER_EYE) {
                    // Similar to travel menu, ideally use PDC. For now match by name.
                    String wpName = itemName;
                    for (UUID lid : wp.getLinkedWaypointIds()) {
                        Waypoint lwp = plugin.getWaypointManager().getWaypoint(lid);
                        if (lwp != null && lwp.getName().equals(wpName)) {
                            plugin.getWaypointManager().unlinkWaypoints(wp.getId(), lid);
                            openLinksMenu(player, wp);
                            break;
                        }
                    }
                }
                break;
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    public boolean hasPendingRename(UUID playerId) {
        return pendingRenames.containsKey(playerId);
    }

    public void processRename(Player player, String newName) {
        UUID wpId = pendingRenames.remove(player.getUniqueId());
        if (wpId == null) return;
        if (newName.equalsIgnoreCase("cancel")) {
            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cRename cancelled."));
            return;
        }
        Waypoint wp = plugin.getWaypointManager().getWaypoint(wpId);
        if (wp != null) {
            wp.setName(newName);
            plugin.getWaypointRenderer().updateLabel(wpId, newName, wp.getOwnerName());
            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aWaypoint renamed to " + newName));
            Bukkit.getScheduler().runTask(plugin, () -> openManagementMenu(player, wp));
        }
    }

    /**
     * Clean up all GUI state for a player (called on quit to prevent memory leaks).
     */
    public void cleanupPlayer(UUID playerId) {
        sessions.remove(playerId);
        pendingRenames.remove(playerId);
    }
}
