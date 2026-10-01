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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class WaypointGUI {
    private final TirnueWaypoints plugin;
    private final NamespacedKey KEY_TARGET_WP;
    private final NamespacedKey KEY_TRUSTED_UUID;
    private final Map<UUID, UUID> pendingRenames = new HashMap<>();
    private final Map<UUID, UUID> pendingFeeChanges = new HashMap<>();
    private final Map<UUID, UUID> pendingCreates = new HashMap<>();
    private final Map<UUID, UUID> pendingTrusts = new HashMap<>();

    public void startPendingCreate(UUID playerId, UUID waypointId) {
        pendingCreates.put(playerId, waypointId);
    }

    public boolean hasPendingCreate(UUID playerId) {
        return pendingCreates.containsKey(playerId);
    }

    public void processCreate(Player player, String input) {
        UUID wpId = pendingCreates.remove(player.getUniqueId());
        if (wpId == null) return;
        Waypoint wp = plugin.getWaypointManager().getWaypoint(wpId);
        if (wp == null) return;
        
        if (input.equalsIgnoreCase("cancel")) {
            // Delete the unnamed waypoint
            plugin.getWaypointManager().deleteWaypoint(wpId);
            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cWaypoint creation cancelled."));
            return;
        }
        
        // Set the name
        wp.setName(input);
        plugin.getWaypointManager().saveAsync();
        
        // Now spawn the display entities
        plugin.getWaypointRenderer().spawnWaypoint(wp);
        
        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &a✦ Waypoint '" + input + "' created!"));
        
        // Open management menu
        openManagementMenu(player, wp);
    }

    public boolean hasPendingFeeChange(UUID playerId) {
        return pendingFeeChanges.containsKey(playerId);
    }

    public void processFeeChange(Player player, String input) {
        UUID wpId = pendingFeeChanges.remove(player.getUniqueId());
        if (wpId == null) return;
        if (input.equalsIgnoreCase("cancel")) {
            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cFee change cancelled."));
            return;
        }
        try {
            double fee = Double.parseDouble(input);
            if (Double.isNaN(fee) || Double.isInfinite(fee)) {
                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cInvalid number. Fee change cancelled."));
                return;
            }
            double maxFee = plugin.getConfig().getDouble("rent.max-fee", 1000.0);
            if (fee < 0 || fee > maxFee) {
                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cFee must be between 0 and " + maxFee + "."));
                return;
            }
            Waypoint wp = plugin.getWaypointManager().getWaypoint(wpId);
            if (wp != null) {
                wp.setUsageFee(fee);
                plugin.getWaypointManager().saveAsync();
                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aUsage fee set to $" + String.format("%.2f", fee)));
                Bukkit.getScheduler().runTask(plugin, () -> openManagementMenu(player, wp));
            }
        } catch (NumberFormatException e) {
            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cInvalid number. Fee change cancelled."));
        }
    }

    public enum GUIType { TRAVEL, MANAGEMENT, TRUST, LINKS, REDEEM_CONFIRM, ACTIVITY, CUSTOMIZE, UPGRADE, ADMIN_NETWORK }

    public static class WaypointGuiHolder implements InventoryHolder {
        private Inventory inventory;
        private final GUIType type;
        private final UUID waypointId;
        private int page;

        public WaypointGuiHolder(GUIType type, UUID waypointId, int page) {
            this.type = type;
            this.waypointId = waypointId;
            this.page = page;
        }

        @Override
        public Inventory getInventory() { return inventory; }
        public void setInventory(Inventory inventory) { this.inventory = inventory; }
        public GUIType getType() { return type; }
        public UUID getWaypointId() { return waypointId; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
    }

    public WaypointGUI(TirnueWaypoints plugin) {
        this.plugin = plugin;
        this.KEY_TARGET_WP = new NamespacedKey(plugin, "target_wp_id");
        this.KEY_TRUSTED_UUID = new NamespacedKey(plugin, "trusted_uuid");
    }

    public void startPendingTrust(UUID playerId, UUID waypointId) {
        pendingTrusts.put(playerId, waypointId);
    }

    public boolean hasPendingTrust(UUID playerId) {
        return pendingTrusts.containsKey(playerId);
    }

    public void processTrust(Player player, String input) {
        UUID wpId = pendingTrusts.remove(player.getUniqueId());
        if (wpId == null) return;
        Waypoint wp = plugin.getWaypointManager().getWaypoint(wpId);
        if (wp == null) return;

        if (input.equalsIgnoreCase("cancel")) {
            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cTrust addition cancelled."));
            Bukkit.getScheduler().runTask(plugin, () -> openTrustMenu(player, wp));
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(input);
        if (target == null || target.getUniqueId() == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cPlayer '" + input + "' not found!"));
            Bukkit.getScheduler().runTask(plugin, () -> openTrustMenu(player, wp));
            return;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cYou are already the owner of this waypoint!"));
            Bukkit.getScheduler().runTask(plugin, () -> openTrustMenu(player, wp));
            return;
        }

        plugin.getWaypointManager().trustPlayer(wp.getId(), target.getUniqueId());
        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &a✦ Successfully trusted &e" + (target.getName() != null ? target.getName() : input) + "&a!"));
        Bukkit.getScheduler().runTask(plugin, () -> openTrustMenu(player, wp));
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

    private ItemStack createWaypointItem(Material mat, Waypoint wp, String name, String... lore) {
        ItemStack item = createItem(mat, name, lore);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(KEY_TARGET_WP, PersistentDataType.STRING, wp.getId().toString());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createTrustedHead(UUID trustedId) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(trustedId);
        String name = op.getName() != null ? op.getName() : "Unknown";
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(op);
            skull.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&e" + name));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.translateAlternateColorCodes('&', "&7Status: &aTrusted"));
            lore.add("");
            lore.add(ChatColor.translateAlternateColorCodes('&', "&cClick to revoke trust"));
            skull.setLore(lore);
            skull.getPersistentDataContainer().set(KEY_TRUSTED_UUID, PersistentDataType.STRING, trustedId.toString());
            head.setItemMeta(skull);
        }
        return head;
    }

    private String c(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public void openTravelMenu(Player player, Waypoint fromWaypoint) {
        WaypointGuiHolder holder = new WaypointGuiHolder(GUIType.TRAVEL, fromWaypoint.getId(), 1);
        Inventory inv = Bukkit.createInventory(holder, 54, deserializeTitle("&5✦ Waypoint Travel"));
        holder.setInventory(inv);
        
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++) inv.setItem(i, border);
        inv.setItem(4, createItem(Material.COMPASS, "&5✦ Waypoint Travel", "&7Select a destination to warp"));
        
        for (int i = 45; i < 54; i++) inv.setItem(i, border);
        if (fromWaypoint.isOwner(player.getUniqueId())) {
            inv.setItem(45, createItem(Material.ARROW, "&7Back to Management", "&7Return to waypoint settings"));
        }
        inv.setItem(49, createItem(Material.BARRIER, "&cClose", "&7Exit menu"));

        int slot = 9;
        List<Waypoint> globalWaypoints = plugin.getWaypointManager().getGlobalWaypoints();
        for (Waypoint gWp : globalWaypoints) {
            if (slot > 26) break;
            if (gWp.getId().equals(fromWaypoint.getId())) continue;
            double dist = fromWaypoint.distanceTo(gWp);
            String distStr = dist == -1 ? "Different Dimension" : String.format("%.1f blocks", dist);
            double time = plugin.getTeleportManager().calculateWarmupSeconds(fromWaypoint, gWp);
            inv.setItem(slot++, createWaypointItem(Material.BEACON, gWp, "&6" + gWp.getName(), 
                "&eGlobal Waypoint",
                "&7Distance: &f" + distStr, 
                "&7Warp Time: &f" + String.format("%.1fs", time),
                "",
                "&eClick to warp"));
        }

        slot = 27;
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

        if (linked.isEmpty() && globalWaypoints.isEmpty()) {
            inv.setItem(31, createItem(Material.GRAY_STAINED_GLASS, "&7No destinations available"));
        } else {
            for (Waypoint lWp : linked) {
                if (slot > 44) break;
                double dist = fromWaypoint.distanceTo(lWp);
                String distStr = dist == -1 ? "Different Dimension" : String.format("%.1f blocks", dist);
                double time = plugin.getTeleportManager().calculateWarmupSeconds(fromWaypoint, lWp);
                
                boolean isFav = plugin.getWaypointManager().isFavorite(pId, lWp.getId());
                String prefix = isFav ? "&6★ " : "&7☆ ";
                
                inv.setItem(slot++, createWaypointItem(Material.ENDER_EYE, lWp, prefix + "&d" + lWp.getName(),
                    "&7Owner: &f" + lWp.getOwnerName(),
                    "&7Distance: &f" + distStr,
                    "&7Warp Time: &f" + String.format("%.1fs", time),
                    "",
                    "&eClick to warp | &7Shift-click to toggle favorite"));
            }
        }
        
        player.openInventory(inv);
    }

    public void openManagementMenu(Player player, Waypoint wp) {
        WaypointGuiHolder holder = new WaypointGuiHolder(GUIType.MANAGEMENT, wp.getId(), 1);
        Inventory inv = Bukkit.createInventory(holder, 45, deserializeTitle("&6✦ " + wp.getName() + " - Management"));
        holder.setInventory(inv);
        
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++) inv.setItem(i, border);
        for (int i = 36; i < 45; i++) {
            if (i != 36 && i != 40 && i != 44) {
                inv.setItem(i, border);
            }
        }
        
        // Row 2
        inv.setItem(10, createItem(Material.NAME_TAG, "&e✏ Rename", "&7Click to rename"));
        inv.setItem(12, createItem(Material.PLAYER_HEAD, "&a♦ Manage Trust", "&7" + wp.getTrustedPlayers().size() + " trusted players"));
        inv.setItem(14, createItem(Material.IRON_BARS, "&b⚡ Connections", "&7" + wp.getLinkedWaypointIds().size() + " active links"));
        inv.setItem(16, createItem(Material.BOOK, "&6☰ Activity", "&7View recent warp activity"));
        
        // Row 3
        inv.setItem(19, createItem(Material.WRITABLE_BOOK, "&d✉ Link Ledger", "&7Generate a linking code"));
        inv.setItem(21, createItem(Material.PAPER, "&e✦ Guest Pass", "&7Create single-use pass"));
        inv.setItem(23, createItem(Material.PAINTING, "&d✿ Customize", "&7Particles & crystal"));
        
        String tier = wp.getTier();
        String nextTier = "none";
        if (tier.equalsIgnoreCase("BASIC")) nextTier = "ADVANCED";
        else if (tier.equalsIgnoreCase("ADVANCED")) nextTier = "MASTER";
        
        if (nextTier.equals("none")) {
            inv.setItem(25, createItem(Material.ANVIL, "&6⬆ Upgrade", "&7Max tier reached"));
        } else {
            double cost = plugin.getConfig().getDouble("tiers." + nextTier.toLowerCase() + ".upgrade-cost-economy", 0);
            inv.setItem(25, createItem(Material.ANVIL, "&6⬆ Upgrade", "&7Current tier: &f" + tier, "&7Next tier: &f" + nextTier, "&7Cost: &a$" + cost + " &7+ items"));
        }

        // Row 4
        inv.setItem(29, createItem(Material.LODESTONE, "&f✦ " + wp.getName(),
            "&7Owner: &f" + wp.getOwnerName(),
            "&7World: &f" + wp.getWorldName(),
            "&7Coords: &f" + (int)wp.getX() + ", " + (int)wp.getY() + ", " + (int)wp.getZ(),
            "&7Tier: &f" + tier,
            "&7Created: &f" + new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date(wp.getCreatedAt()))));
            
        inv.setItem(31, createItem(Material.GOLD_INGOT, "&6$ Usage Fee", "&7Current fee: &a$" + String.format("%.2f", wp.getUsageFee()), "&eClick to change"));
        inv.setItem(33, createItem(Material.BOOK, "&a✉ Redeem Ledger", "&7Hold ledger & click"));

        // Row 5
        inv.setItem(36, createItem(Material.BARRIER, "&cClose", "&7Exit menu"));
        inv.setItem(40, createItem(Material.ENDER_PEARL, "&5✦ Travel", "&7Open travel menu"));
        inv.setItem(44, createItem(Material.TNT, "&c✖ Destroy", "&7Shift-click to confirm"));

        player.openInventory(inv);
    }

    public void openCustomizeMenu(Player player, Waypoint wp) {
        WaypointGuiHolder holder = new WaypointGuiHolder(GUIType.CUSTOMIZE, wp.getId(), 1);
        Inventory inv = Bukkit.createInventory(holder, 27, deserializeTitle("&d✦ Customize - " + wp.getName()));
        holder.setInventory(inv);
        
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
        
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 18; i < 27; i++) inv.setItem(i, border);
        inv.setItem(18, createItem(Material.ARROW, "&7Back", "&7Return to management menu"));
        inv.setItem(22, createItem(Material.ARROW, "&7Back", "&7Return to management menu"));
        
        player.openInventory(inv);
    }

    public void openActivityMenu(Player player, Waypoint wp) {
        WaypointGuiHolder holder = new WaypointGuiHolder(GUIType.ACTIVITY, wp.getId(), 1);
        Inventory inv = Bukkit.createInventory(holder, 54, deserializeTitle("&6✦ Activity - " + wp.getName()));
        holder.setInventory(inv);
        
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, border);

        List<Waypoint.ActivityEntry> log = wp.getActivityLog();
        if (log == null || log.isEmpty()) {
            inv.setItem(22, createItem(Material.GRAY_STAINED_GLASS_PANE, "&7No recent activity"));
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
        
        inv.setItem(45, createItem(Material.ARROW, "&7Back", "&7Return to management menu"));
        inv.setItem(49, createItem(Material.ARROW, "&7Back", "&7Return to management menu"));
        inv.setItem(53, createItem(Material.ARROW, "&7Back", "&7Return to management menu"));
        
        player.openInventory(inv);
    }

    public void openTrustMenu(Player player, Waypoint wp) {
        WaypointGuiHolder holder = new WaypointGuiHolder(GUIType.TRUST, wp.getId(), 1);
        Inventory inv = Bukkit.createInventory(holder, 54, deserializeTitle("&a✦ Trust - " + wp.getName()));
        holder.setInventory(inv);
        
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, border);

        int slot = 0;
        for (UUID trustedId : wp.getTrustedPlayers()) {
            if (slot >= 45) break;
            inv.setItem(slot++, createTrustedHead(trustedId));
        }

        inv.setItem(45, createItem(Material.ARROW, "&7Back", "&7Return to management menu"));
        inv.setItem(49, createItem(Material.EMERALD, "&aAdd Trust", "&7Click to type player name in chat"));
        inv.setItem(53, createItem(Material.ARROW, "&7Back", "&7Return to management menu"));

        player.openInventory(inv);
    }

    public void openLinksMenu(Player player, Waypoint wp) {
        WaypointGuiHolder holder = new WaypointGuiHolder(GUIType.LINKS, wp.getId(), 1);
        Inventory inv = Bukkit.createInventory(holder, 54, deserializeTitle("&b✦ Links - " + wp.getName()));
        holder.setInventory(inv);
        
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, border);

        int slot = 0;
        for (UUID linkId : wp.getLinkedWaypointIds()) {
            if (slot >= 45) break;
            Waypoint lWp = plugin.getWaypointManager().getWaypoint(linkId);
            if (lWp != null) {
                double dist = wp.distanceTo(lWp);
                String distStr = dist == -1 ? "Different Dimension" : String.format("%.1f blocks", dist);
                inv.setItem(slot++, createWaypointItem(Material.ENDER_EYE, lWp, "&d" + lWp.getName(),
                    "&7Owner: &f" + lWp.getOwnerName(),
                    "&7Distance: &f" + distStr,
                    "",
                    "&cClick to sever connection"));
            }
        }

        inv.setItem(45, createItem(Material.ARROW, "&7Back", "&7Return to management menu"));
        inv.setItem(49, createItem(Material.ARROW, "&7Back", "&7Return to management menu"));
        inv.setItem(53, createItem(Material.ARROW, "&7Back", "&7Return to management menu"));
        
        player.openInventory(inv);
    }

    public void openUpgradeMenu(Player player, Waypoint wp) {
        WaypointGuiHolder holder = new WaypointGuiHolder(GUIType.UPGRADE, wp.getId(), 1);
        Inventory inv = Bukkit.createInventory(holder, 27, deserializeTitle("&6⬆ Upgrade - " + wp.getName()));
        holder.setInventory(inv);
        String tier = wp.getTier();
        String nextTier = tier.equalsIgnoreCase("BASIC") ? "ADVANCED" : "MASTER";
        
        double cost = plugin.getConfig().getDouble("tiers." + nextTier.toLowerCase() + ".upgrade-cost-economy", 0);
        int maxLinks = plugin.getConfig().getInt("tiers." + nextTier.toLowerCase() + ".max-links", 0);
        int maxTrusted = plugin.getConfig().getInt("tiers." + nextTier.toLowerCase() + ".max-trusted", 0);
        double speedMult = plugin.getConfig().getDouble("tiers." + nextTier.toLowerCase() + ".teleport-speed-multiplier", 1.0);
        
        List<String> lore = new ArrayList<>();
        lore.add("&7Upgrade to &f" + nextTier);
        lore.add("&7Cost: &a$" + cost);
        org.bukkit.configuration.ConfigurationSection items = plugin.getConfig().getConfigurationSection("tiers." + nextTier.toLowerCase() + ".upgrade-cost-items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                lore.add("&7- " + items.getInt(key) + "x " + key);
            }
        }
        lore.add("");
        lore.add("&eClick to confirm");
        
        inv.setItem(13, createItem(Material.EMERALD_BLOCK, "&aConfirm Upgrade", lore.toArray(new String[0])));
        inv.setItem(11, createItem(Material.EXPERIENCE_BOTTLE, "&bBenefits", 
            "&7Max Links: &f" + maxLinks,
            "&7Max Trusted: &f" + maxTrusted,
            "&7Speed Multiplier: &f" + speedMult + "x"));
        inv.setItem(18, createItem(Material.ARROW, "&7Back", "&7Return to management menu"));
        inv.setItem(22, createItem(Material.BARRIER, "&cCancel", "&7Return to management menu"));

        player.openInventory(inv);
    }

    public void openAdminNetworkMenu(Player player) {
        openAdminNetworkMenu(player, 1);
    }

    public void openAdminNetworkMenu(Player player, int page) {
        WaypointGuiHolder holder = new WaypointGuiHolder(GUIType.ADMIN_NETWORK, null, page);
        Inventory inv = Bukkit.createInventory(holder, 54, deserializeTitle("&4⚙ Waypoint Network"));
        holder.setInventory(inv);
        List<Waypoint> allWps = new ArrayList<>(plugin.getWaypointManager().getAllWaypoints());
        allWps.sort(Comparator.comparing(Waypoint::getName));
        
        int start = (page - 1) * 45;
        int end = Math.min(start + 45, allWps.size());
        
        for (int i = start; i < end; i++) {
            Waypoint wp = allWps.get(i);
            Material mat = wp.isGlobal() ? Material.BEACON : Material.ENDER_EYE;
            inv.setItem(i - start, createWaypointItem(mat, wp, "&f" + wp.getName(),
                "&7Owner: &f" + wp.getOwnerName(),
                "&7World: &f" + wp.getWorldName(),
                "&7Coords: &f" + (int)wp.getX() + ", " + (int)wp.getY() + ", " + (int)wp.getZ(),
                "&7Tier: &f" + wp.getTier(),
                "&7Links: &f" + wp.getLinkedWaypointIds().size(),
                "&7Trust: &f" + wp.getTrustedPlayers().size(),
                "&7Usage Fee: &a$" + String.format("%.2f", wp.getUsageFee()),
                "",
                "&eClick to teleport"));
        }
        
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, border);
        if (page > 1) inv.setItem(45, createItem(Material.ARROW, "&7Previous Page"));
        inv.setItem(49, createItem(Material.BARRIER, "&cClose"));
        if (end < allWps.size()) inv.setItem(53, createItem(Material.ARROW, "&7Next Page"));
        
        player.openInventory(inv);
    }

    public void handleClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof WaypointGuiHolder holder)) {
            return;
        }

        // Security guarantee: Unconditionally cancel ALL clicks in our custom GUI!
        event.setCancelled(true);

        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getInventory().getSize()) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }

        int slot = event.getRawSlot();

        // 1. ADMIN_NETWORK
        if (holder.getType() == GUIType.ADMIN_NETWORK) {
            if (slot == 49) {
                player.closeInventory();
                return;
            }
            if (slot == 45 && clicked.getType() == Material.ARROW) {
                if (holder.getPage() > 1) openAdminNetworkMenu(player, holder.getPage() - 1);
                return;
            }
            if (slot == 53 && clicked.getType() == Material.ARROW) {
                openAdminNetworkMenu(player, holder.getPage() + 1);
                return;
            }
            if (clicked.hasItemMeta()) {
                String targetIdStr = clicked.getItemMeta().getPersistentDataContainer().get(KEY_TARGET_WP, PersistentDataType.STRING);
                if (targetIdStr != null) {
                    try {
                        UUID targetId = UUID.fromString(targetIdStr);
                        Waypoint targetWp = plugin.getWaypointManager().getWaypoint(targetId);
                        if (targetWp != null) {
                            org.bukkit.Location dest = targetWp.toBukkitLocation();
                            if (dest != null) {
                                player.closeInventory();
                                player.teleport(dest);
                                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aTeleported to waypoint &e" + targetWp.getName()));
                            }
                        }
                    } catch (IllegalArgumentException ignored) {}
                }
            }
            return;
        }

        // 2. Waypoint menus
        Waypoint wp = plugin.getWaypointManager().getWaypoint(holder.getWaypointId());
        if (wp == null) {
            player.closeInventory();
            return;
        }

        switch (holder.getType()) {
            case TRAVEL:
                if (slot == 49) {
                    player.closeInventory();
                    return;
                }
                if (slot == 45 && wp.isOwner(player.getUniqueId())) {
                    openManagementMenu(player, wp);
                    return;
                }
                if (clicked.hasItemMeta()) {
                    String targetIdStr = clicked.getItemMeta().getPersistentDataContainer().get(KEY_TARGET_WP, PersistentDataType.STRING);
                    if (targetIdStr != null) {
                        try {
                            UUID targetId = UUID.fromString(targetIdStr);
                            Waypoint dest = plugin.getWaypointManager().getWaypoint(targetId);
                            if (dest != null) {
                                if (event.isShiftClick()) {
                                    plugin.getWaypointManager().toggleFavorite(player.getUniqueId(), dest.getId());
                                    openTravelMenu(player, wp);
                                } else {
                                    player.closeInventory();
                                    plugin.getTeleportManager().startTeleport(player, wp, dest);
                                }
                            }
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
                break;

            case MANAGEMENT:
                if (slot == 10) { // Rename
                    player.closeInventory();
                    pendingRenames.put(player.getUniqueId(), wp.getId());
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aType the new name for your waypoint in chat, or type 'cancel' to abort."));
                } else if (slot == 12) { // Manage Trust
                    openTrustMenu(player, wp);
                } else if (slot == 14) { // Connected Links
                    openLinksMenu(player, wp);
                } else if (slot == 16) { // Activity Log
                    openActivityMenu(player, wp);
                } else if (slot == 19) { // Generate Ledger
                    ItemStack ledger = plugin.getLinkManager().generateLedger(wp);
                    player.getInventory().addItem(ledger);
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aLink ledger generated."));
                } else if (slot == 21) { // Guest Pass
                    ItemStack pass = plugin.getLinkManager().generateGuestPass(wp);
                    player.getInventory().addItem(pass);
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aGuest pass generated."));
                } else if (slot == 23) { // Customize Appearance
                    openCustomizeMenu(player, wp);
                } else if (slot == 25) { // Upgrade
                    if (!wp.getTier().equalsIgnoreCase("MASTER")) {
                        openUpgradeMenu(player, wp);
                    }
                } else if (slot == 31) { // Set Usage Fee
                    player.closeInventory();
                    pendingFeeChanges.put(player.getUniqueId(), wp.getId());
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aType the new usage fee for your waypoint in chat, or type 'cancel' to abort."));
                } else if (slot == 33) { // Redeem Ledger
                    ItemStack hand = player.getInventory().getItemInMainHand();
                    if (plugin.getLinkManager().isLedger(hand)) {
                        if (plugin.getLinkManager().redeemLedger(player, wp, hand)) {
                            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aSuccessfully linked waypoints!"));
                            openManagementMenu(player, wp);
                        }
                    } else {
                        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cYou must be holding a Link Ledger to redeem it."));
                    }
                } else if (slot == 36) { // Close
                    player.closeInventory();
                } else if (slot == 40) { // Travel
                    openTravelMenu(player, wp);
                } else if (slot == 44) { // Destroy
                    if (event.isShiftClick()) {
                        player.closeInventory();
                        plugin.getWaypointManager().deleteWaypoint(wp.getId());
                        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cWaypoint destroyed."));
                    } else {
                        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cShift-click to confirm destruction."));
                    }
                }
                break;

            case ACTIVITY:
                if (slot == 45 || slot == 49 || slot == 53) {
                    openManagementMenu(player, wp);
                }
                break;

            case CUSTOMIZE:
                if (slot == 18 || slot == 22) {
                    openManagementMenu(player, wp);
                } else if (slot >= 0 && slot < 9) {
                    String[] partNames = {"ENCHANT", "PORTAL", "REVERSE_PORTAL", "FLAME", "SOUL_FIRE_FLAME", "END_ROD", "HEART", "DRIPPING_HONEY", "CHERRY_LEAVES"};
                    wp.setParticleType(partNames[slot]);
                    plugin.getWaypointManager().saveAsync();
                    openCustomizeMenu(player, wp);
                } else if (slot >= 9 && slot < 18) {
                    Material[] crystals = {Material.AMETHYST_SHARD, Material.DIAMOND, Material.EMERALD, Material.NETHER_STAR, Material.END_CRYSTAL, Material.ENDER_EYE, Material.HEART_OF_THE_SEA, Material.PRISMARINE_CRYSTALS, Material.GLOWSTONE_DUST};
                    wp.setCrystalMaterial(crystals[slot - 9].name());
                    plugin.getWaypointManager().saveAsync();
                    plugin.getWaypointRenderer().despawnWaypoint(wp.getId());
                    plugin.getWaypointRenderer().spawnWaypoint(wp);
                    openCustomizeMenu(player, wp);
                }
                break;

            case TRUST:
                if (slot == 45 || slot == 53) { // Back
                    openManagementMenu(player, wp);
                    return;
                }
                if (slot == 49) { // Add trust
                    player.closeInventory();
                    startPendingTrust(player.getUniqueId(), wp.getId());
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &a✦ Type the player's username in chat &7(or 'cancel'):"));
                    return;
                }
                if (clicked.hasItemMeta()) {
                    String trustedUuidStr = clicked.getItemMeta().getPersistentDataContainer().get(KEY_TRUSTED_UUID, PersistentDataType.STRING);
                    if (trustedUuidStr != null) {
                        try {
                            UUID targetUUID = UUID.fromString(trustedUuidStr);
                            OfflinePlayer op = Bukkit.getOfflinePlayer(targetUUID);
                            plugin.getWaypointManager().untrustPlayer(wp.getId(), targetUUID);
                            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cRemoved trust for " + (op.getName() != null ? op.getName() : "player") + "."));
                            openTrustMenu(player, wp);
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
                break;

            case LINKS:
                if (slot == 45 || slot == 49 || slot == 53) { // Back
                    openManagementMenu(player, wp);
                    return;
                }
                if (clicked.hasItemMeta()) {
                    String targetIdStr = clicked.getItemMeta().getPersistentDataContainer().get(KEY_TARGET_WP, PersistentDataType.STRING);
                    if (targetIdStr != null) {
                        try {
                            UUID targetId = UUID.fromString(targetIdStr);
                            plugin.getWaypointManager().unlinkWaypoints(wp.getId(), targetId);
                            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cSevered connection with waypoint."));
                            openLinksMenu(player, wp);
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
                break;

            case UPGRADE:
                if (slot == 18 || slot == 22) { // Back / Cancel
                    openManagementMenu(player, wp);
                    return;
                }
                if (slot == 13) {
                    String tier = wp.getTier();
                    String nextTier = tier.equalsIgnoreCase("BASIC") ? "ADVANCED" : "MASTER";
                    
                    net.milkbowl.vault.economy.Economy econ = plugin.getEconomy();
                    double cost = plugin.getConfig().getDouble("tiers." + nextTier.toLowerCase() + ".upgrade-cost-economy", 0);
                    if (econ != null && !econ.has(player, cost)) {
                        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cNot enough money!"));
                        return;
                    }
                    
                    // Check items
                    org.bukkit.configuration.ConfigurationSection items = plugin.getConfig().getConfigurationSection("tiers." + nextTier.toLowerCase() + ".upgrade-cost-items");
                    if (items != null) {
                        for (String key : items.getKeys(false)) {
                            Material mat = Material.matchMaterial(key);
                            int amount = items.getInt(key);
                            if (mat != null && !player.getInventory().containsAtLeast(new ItemStack(mat), amount)) {
                                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cMissing items: " + amount + "x " + key));
                                return;
                            }
                        }
                        // Remove items
                        for (String key : items.getKeys(false)) {
                            Material mat = Material.matchMaterial(key);
                            int amount = items.getInt(key);
                            if (mat != null) player.getInventory().removeItem(new ItemStack(mat, amount));
                        }
                    }
                    
                    if (econ != null) econ.withdrawPlayer(player, cost);
                    
                    wp.setTier(nextTier.toUpperCase());
                    plugin.getWaypointManager().saveAsync();
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &a✦ Waypoint upgraded to " + nextTier.toUpperCase() + "!"));
                    openManagementMenu(player, wp);
                }
                break;
        }
    }

    public void handleDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof WaypointGuiHolder) {
            event.setCancelled(true);
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        // No sessions map needed because state is carried directly by WaypointGuiHolder!
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
     * Clean up all chat input state for a player (called on quit to prevent memory leaks).
     */
    public void cleanupPlayer(UUID playerId) {
        pendingRenames.remove(playerId);
        pendingFeeChanges.remove(playerId);
        pendingCreates.remove(playerId);
        pendingTrusts.remove(playerId);
    }
}
