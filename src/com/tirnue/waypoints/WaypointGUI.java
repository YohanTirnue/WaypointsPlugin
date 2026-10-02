package com.tirnue.waypoints;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
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
    private final Map<UUID, UUID> pendingSearches = new HashMap<>();

    public void startPendingSearch(UUID playerId, UUID waypointId) {
        pendingSearches.put(playerId, waypointId);
    }

    public boolean hasPendingSearch(UUID playerId) {
        return pendingSearches.containsKey(playerId);
    }

    public void processSearch(Player player, String query) {
        UUID wpId = pendingSearches.remove(player.getUniqueId());
        if (wpId == null) return;
        Waypoint wp = plugin.getWaypointManager().getWaypoint(wpId);
        if (wp == null) return;

        if (query.equalsIgnoreCase("cancel") || query.equalsIgnoreCase("clear")) {
            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &7Search cancelled."));
            openTravelMenu(player, wp, 1, "ALL", null);
        } else {
            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aSearching destinations for: &e\"" + query + "\""));
            openTravelMenu(player, wp, 1, "ALL", query);
        }
    }

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
        private String filter = "ALL";
        private String searchQuery = null;

        public WaypointGuiHolder(GUIType type, UUID waypointId, int page) {
            this(type, waypointId, page, "ALL", null);
        }

        public WaypointGuiHolder(GUIType type, UUID waypointId, int page, String filter, String searchQuery) {
            this.type = type;
            this.waypointId = waypointId;
            this.page = page;
            this.filter = filter != null ? filter : "ALL";
            this.searchQuery = searchQuery;
        }

        @Override
        public Inventory getInventory() { return inventory; }
        public void setInventory(Inventory inventory) { this.inventory = inventory; }
        public GUIType getType() { return type; }
        public UUID getWaypointId() { return waypointId; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
        public String getFilter() { return filter; }
        public void setFilter(String filter) { this.filter = filter; }
        public String getSearchQuery() { return searchQuery; }
        public void setSearchQuery(String searchQuery) { this.searchQuery = searchQuery; }
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

    private ItemStack createFilterItem(Material mat, String name, boolean active, String... lore) {
        ItemStack item = createItem(mat, name, lore);
        if (active) {
            item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    private String c(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public void openTravelMenu(Player player, Waypoint fromWaypoint) {
        openTravelMenu(player, fromWaypoint, 1, "ALL", null);
    }

    public void openTravelMenu(Player player, Waypoint fromWaypoint, int page, String filter, String searchQuery) {
        String activeFilter = filter != null ? filter.toUpperCase() : "ALL";
        WaypointGuiHolder holder = new WaypointGuiHolder(GUIType.TRAVEL, fromWaypoint.getId(), page, activeFilter, searchQuery);
        Inventory inv = Bukkit.createInventory(holder, 54, deserializeTitle("&5✦ Waypoint Travel"));
        holder.setInventory(inv);

        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");

        // Top Filter Bar (Slots 0 to 8)
        // Slot 0: All
        inv.setItem(0, createFilterItem(Material.NETHER_STAR, (activeFilter.equals("ALL") ? "&b&l✦ All Destinations" : "&7✦ All Destinations"),
                activeFilter.equals("ALL"),
                "&7View all accessible waypoints.",
                "&7Includes global & linked destinations.",
                "",
                (activeFilter.equals("ALL") ? "&a✔ Currently Active" : "&eClick to show all")));

        // Slot 1: Favorites
        inv.setItem(1, createFilterItem(Material.GOLD_INGOT, (activeFilter.equals("FAVORITES") ? "&6&l★ Favorites" : "&7★ Favorites"),
                activeFilter.equals("FAVORITES"),
                "&7View your bookmarked waypoints.",
                "&7Shift-click any waypoint to favorite it!",
                "",
                (activeFilter.equals("FAVORITES") ? "&a✔ Currently Active" : "&eClick to filter favorites")));

        // Slot 2: Global
        inv.setItem(2, createFilterItem(Material.BEACON, (activeFilter.equals("GLOBAL") ? "&e&l🌐 Global / Events" : "&7🌐 Global / Events"),
                activeFilter.equals("GLOBAL"),
                "&7Server-wide public event hubs and",
                "&7community landmarks.",
                "",
                (activeFilter.equals("GLOBAL") ? "&a✔ Currently Active" : "&eClick to filter global")));

        // Slot 3: Linked
        inv.setItem(3, createFilterItem(Material.ENDER_EYE, (activeFilter.equals("LINKED") ? "&d&l🔗 Linked Bases" : "&7🔗 Linked Bases"),
                activeFilter.equals("LINKED"),
                "&7Waypoints connected directly to this network",
                "&7via Link Ledgers.",
                "",
                (activeFilter.equals("LINKED") ? "&a✔ Currently Active" : "&eClick to filter linked")));

        // Slot 5: Nearest
        inv.setItem(5, createFilterItem(Material.CLOCK, (activeFilter.equals("NEAREST") ? "&a&l📍 Nearest to Me" : "&7📍 Nearest to Me"),
                activeFilter.equals("NEAREST"),
                "&7Sorted by physical distance",
                "&7to this waypoint.",
                "",
                (activeFilter.equals("NEAREST") ? "&a✔ Currently Active" : "&eClick to sort by nearest")));

        // Slot 6: Search
        inv.setItem(6, createItem(Material.SPYGLASS, "&e🔍 Search Destinations",
                "&7Find destinations by name.",
                (searchQuery != null ? "&7Active Query: &e\"" + searchQuery + "\"" : "&7No active search filter"),
                "",
                "&eClick to search in chat"));

        // Slot 7: Clear search or border
        if (searchQuery != null && !searchQuery.isEmpty()) {
            inv.setItem(7, createItem(Material.BARRIER, "&c✖ Clear Search",
                    "&7Active query: &e\"" + searchQuery + "\"",
                    "",
                    "&cClick to reset search"));
        } else {
            inv.setItem(7, border);
        }

        inv.setItem(8, border);

        // Destination collection & filtering
        UUID pId = player.getUniqueId();
        List<Waypoint> globalWaypoints = plugin.getWaypointManager().getGlobalWaypoints();
        List<Waypoint> linkedWaypoints = new ArrayList<>();
        for (UUID lid : fromWaypoint.getLinkedWaypointIds()) {
            Waypoint lWp = plugin.getWaypointManager().getWaypoint(lid);
            if (lWp != null) linkedWaypoints.add(lWp);
        }

        List<Waypoint> candidates = new ArrayList<>();
        if ("GLOBAL".equals(activeFilter)) {
            for (Waypoint g : globalWaypoints) {
                if (!g.getId().equals(fromWaypoint.getId())) {
                    candidates.add(g);
                }
            }
        } else if ("LINKED".equals(activeFilter)) {
            candidates.addAll(linkedWaypoints);
        } else if ("FAVORITES".equals(activeFilter)) {
            for (Waypoint g : globalWaypoints) {
                if (!g.getId().equals(fromWaypoint.getId()) && plugin.getWaypointManager().isFavorite(pId, g.getId())) {
                    candidates.add(g);
                }
            }
            for (Waypoint l : linkedWaypoints) {
                if (plugin.getWaypointManager().isFavorite(pId, l.getId()) && !candidates.contains(l)) {
                    candidates.add(l);
                }
            }
        } else {
            // "ALL" or "NEAREST"
            for (Waypoint g : globalWaypoints) {
                if (!g.getId().equals(fromWaypoint.getId())) {
                    candidates.add(g);
                }
            }
            for (Waypoint l : linkedWaypoints) {
                if (!candidates.contains(l)) {
                    candidates.add(l);
                }
            }
        }

        // Apply search query filter if set
        if (searchQuery != null && !searchQuery.trim().isEmpty()) {
            String q = searchQuery.trim().toLowerCase();
            candidates.removeIf(w -> {
                boolean canSeeName = w.isGlobal()
                        || plugin.getWaypointManager().hasDiscovered(pId, w.getId())
                        || w.isOwner(pId)
                        || player.hasPermission("tirnue.waypoints.admin");
                if (!canSeeName) return true;
                return !w.getName().toLowerCase().contains(q);
            });
        }

        // Sorting
        if ("NEAREST".equals(activeFilter)) {
            candidates.sort((w1, w2) -> {
                double d1 = fromWaypoint.distanceTo(w1);
                double d2 = fromWaypoint.distanceTo(w2);
                if (d1 == -1 && d2 == -1) return w1.getName().compareToIgnoreCase(w2.getName());
                if (d1 == -1) return 1;
                if (d2 == -1) return -1;
                return Double.compare(d1, d2);
            });
        } else {
            candidates.sort((w1, w2) -> {
                boolean f1 = plugin.getWaypointManager().isFavorite(pId, w1.getId());
                boolean f2 = plugin.getWaypointManager().isFavorite(pId, w2.getId());
                if (f1 && !f2) return -1;
                if (!f1 && f2) return 1;
                return w1.getName().compareToIgnoreCase(w2.getName());
            });
        }

        int totalItems = candidates.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / 36.0));
        int currentPage = Math.max(1, Math.min(page, totalPages));
        holder.setPage(currentPage);

        // Slot 4: Info status
        List<String> infoLore = new ArrayList<>();
        infoLore.add("&7Origin: &f" + fromWaypoint.getName());
        infoLore.add("&7Current Filter: &b" + activeFilter);
        if (searchQuery != null && !searchQuery.isEmpty()) {
            infoLore.add("&7Active Search: &e\"" + searchQuery + "\"");
        }
        infoLore.add("&7Total Destinations: &f" + totalItems);
        infoLore.add("&7Page: &f" + currentPage + " &7/ &f" + totalPages);
        inv.setItem(4, createItem(Material.COMPASS, "&5✦ Waypoint Travel", infoLore.toArray(new String[0])));

        // Populate destination slots: 9 to 44 (36 items per page)
        int startIndex = (currentPage - 1) * 36;
        int endIndex = Math.min(startIndex + 36, totalItems);

        if (totalItems == 0) {
            if (searchQuery != null && !searchQuery.isEmpty()) {
                inv.setItem(22, createItem(Material.BARRIER, "&cNo destinations found",
                        "&7No waypoints matched: &e\"" + searchQuery + "\"",
                        "&7Click slot 7 to clear filter."));
            } else if ("FAVORITES".equals(activeFilter)) {
                inv.setItem(22, createItem(Material.GOLD_INGOT, "&eNo Favorites Saved",
                        "&7Shift-click any destination in the",
                        "&7travel menu to bookmark it!"));
            } else if ("LINKED".equals(activeFilter)) {
                inv.setItem(22, createItem(Material.IRON_BARS, "&7No Linked Waypoints",
                        "&7Use a Link Ledger to connect other",
                        "&7waypoints to this network."));
            } else {
                inv.setItem(22, createItem(Material.GRAY_STAINED_GLASS, "&7No destinations available"));
            }
        } else {
            for (int i = startIndex; i < endIndex; i++) {
                Waypoint dest = candidates.get(i);
                int destSlot = 9 + (i - startIndex);

                boolean discovered = dest.isGlobal()
                        || plugin.getWaypointManager().hasDiscovered(pId, dest.getId())
                        || dest.isOwner(pId)
                        || player.hasPermission("tirnue.waypoints.admin");

                if (discovered) {
                    double dist = fromWaypoint.distanceTo(dest);
                    String distStr = dist == -1 ? "Different Dimension" : String.format("%.1f blocks", dist);
                    double time = plugin.getTeleportManager().calculateWarmupSeconds(fromWaypoint, dest);
                    boolean isFav = plugin.getWaypointManager().isFavorite(pId, dest.getId());
                    String prefix = isFav ? "&6★ " : "&7☆ ";

                    boolean isDepleted = plugin.getConfigManager().isDurabilityEnabled() && !dest.isGlobal() && dest.isDepleted();

                    if (isDepleted) {
                        inv.setItem(destSlot, createWaypointItem(Material.CRACKED_STONE_BRICKS, dest,
                                prefix + "&c" + dest.getName() + " &4(DEPLETED)",
                                "&7Owner: &f" + dest.getOwnerName(),
                                "&7Distance: &f" + distStr,
                                "&4✖ Depleted Integrity: 0 / " + dest.getMaxDurability(),
                                "&cRequires owner repair before warps can arrive!",
                                "",
                                "&7Shift-click to toggle favorite"));
                    } else if (dest.isGlobal()) {
                        List<String> dLore = new ArrayList<>();
                        dLore.add("&eGlobal Waypoint &7(Public/Event)");
                        dLore.add("&7Distance: &f" + distStr);
                        dLore.add("&7Warp Time: &f" + String.format("%.1fs", time));
                        if (plugin.getConfigManager().isDurabilityEnabled()) {
                            dLore.add("&7Integrity: &a∞ Infinite (Global)");
                        }
                        if (plugin.getConfigManager().isGraceEnabled()) {
                            dLore.add("&6✦ Wayfarer's Grace: &eMaster Divine Sanctuary");
                        }
                        dLore.add("");
                        dLore.add("&eClick to warp | &7Shift-click to favorite");

                        inv.setItem(destSlot, createWaypointItem(Material.BEACON, dest,
                                prefix + "&6✦ " + dest.getName(), dLore.toArray(new String[0])));
                    } else {
                        List<String> dLore = new ArrayList<>();
                        dLore.add("&7Owner: &f" + dest.getOwnerName());
                        dLore.add("&7Distance: &f" + distStr);
                        dLore.add("&7Warp Time: &f" + String.format("%.1fs", time));
                        if (plugin.getConfigManager().isDurabilityEnabled()) {
                            dLore.add("&7Integrity: &a" + dest.getDurability() + "&7/&a" + dest.getMaxDurability());
                        }
                        if (plugin.getConfigManager().isGraceEnabled()) {
                            String dTier = dest.getTier() != null ? dest.getTier().toUpperCase() : "BASIC";
                            if (dTier.equals("MASTER")) {
                                dLore.add("&6✦ Wayfarer's Grace: &eTier II Master Sanctuary");
                            } else if (dTier.equals("ADVANCED")) {
                                dLore.add("&a✦ Wayfarer's Grace: &fTier I Arrival Protection");
                            } else {
                                dLore.add("&8No arrival grace (Basic waypoint)");
                            }
                        }
                        if (dest.getUsageFee() > 0 && !dest.isOwner(pId)) {
                            dLore.add("&6Usage Fee: &a$" + String.format("%.2f", dest.getUsageFee()));
                        }
                        dLore.add("");
                        dLore.add("&eClick to warp | &7Shift-click to favorite");

                        inv.setItem(destSlot, createWaypointItem(Material.ENDER_EYE, dest,
                                prefix + "&d" + dest.getName(), dLore.toArray(new String[0])));
                    }
                } else {
                    inv.setItem(destSlot, createWaypointItem(Material.STRUCTURE_VOID, dest,
                            "&8??? &7(Undiscovered Link)",
                            "&7Owner: &8Unknown",
                            "&c✖ Not yet discovered!",
                            "",
                            "&7Explore the world to find and",
                            "&7attune to this waypoint in person.",
                            "",
                            "&8(Locked until discovered)"));
                }
            }
        }

        // Bottom Navigation Bar (Slots 45 to 53)
        for (int i = 45; i < 54; i++) inv.setItem(i, border);

        // Slot 45: Previous Page or Back to Management
        if (currentPage > 1) {
            inv.setItem(45, createItem(Material.ARROW, "&a« Previous Page", "&7Go to page " + (currentPage - 1)));
        } else if (fromWaypoint.isOwner(player.getUniqueId())) {
            inv.setItem(45, createItem(Material.ARROW, "&7Back to Management", "&7Return to waypoint settings"));
        }

        // Slot 49: Close
        inv.setItem(49, createItem(Material.BARRIER, "&cClose", "&7Exit menu"));

        // Slot 53: Next Page
        if (currentPage < totalPages) {
            inv.setItem(53, createItem(Material.ARROW, "&aNext Page »", "&7Go to page " + (currentPage + 1)));
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
        inv.setItem(20, createItem(Material.MAP, "&d✦ Inscribe Warp Scroll", "&7Create a single-use scroll", "&7bound to this waypoint.", "", "&7Cost: &f1 Blank Scroll", "&7(or &f1 Pearl + 1 Paper&7)", "", "&eClick to inscribe"));
        inv.setItem(21, createItem(Material.PAPER, "&e✦ Guest Pass", "&7Create single-use pass"));

        // Slot 22: Structural Integrity & Durability Repair
        if (plugin.getConfigManager().isDurabilityEnabled()) {
            int curDur = wp.getDurability();
            int maxDur = wp.getMaxDurability();
            double ratio = (double) curDur / (double) maxDur;
            int greenBars = (int) Math.round(ratio * 20);
            if (greenBars > 20) greenBars = 20;
            if (greenBars < 0) greenBars = 0;
            int redBars = 20 - greenBars;
            String bar = "&a" + "|".repeat(greenBars) + "&8" + "|".repeat(redBars);

            List<String> durLore = new ArrayList<>();
            durLore.add("&7Structural Integrity:");
            durLore.add(" " + bar + " &f" + curDur + "&7/&f" + maxDur + " &7(" + (int)(ratio * 100) + "%)");
            durLore.add("");
            if (wp.isGlobal()) {
                durLore.add("&a✔ Infinite Durability (Global)");
                durLore.add("&7No repairs required.");
            } else if (curDur >= maxDur) {
                durLore.add("&a✔ Fully Maintained");
                durLore.add("&7Integrity is at maximum capacity.");
            } else {
                int missing = maxDur - curDur;
                double costPerPt = plugin.getConfigManager().getDurabilityCostPerPoint();
                double totalCost = missing * costPerPt;
                if (wp.isDepleted()) {
                    durLore.add("&4✖ STATUS: DEPLETED (WARPS DISABLED)");
                } else if (ratio <= 0.25) {
                    durLore.add("&c⚠ STATUS: CRITICAL DAMAGE");
                } else {
                    durLore.add("&e⚠ STATUS: WEAKENED");
                }
                durLore.add("&7Missing: &e" + missing + " pts");
                durLore.add("&7Repair Cost: &a$" + String.format("%.2f", totalCost) + " &7(Vault)");
                durLore.add("&7Cost per point: &f$" + String.format("%.2f", costPerPt));
                durLore.add("");
                durLore.add("&eClick to repair & restore 100% integrity");
            }

            inv.setItem(22, createItem(Material.SMITHING_TABLE, "&6✦ Structural Integrity & Repair", durLore.toArray(new String[0])));
        }

        inv.setItem(23, createItem(Material.PAINTING, "&d✿ Customize", "&7Particles & crystal"));
        
        String tier = wp.getTier();
        String nextTier = "none";
        if (tier.equalsIgnoreCase("BASIC")) nextTier = "ADVANCED";
        else if (tier.equalsIgnoreCase("ADVANCED")) nextTier = "MASTER";
        
        if (nextTier.equals("none")) {
            inv.setItem(25, createItem(Material.ANVIL, "&6⬆ Upgrade", "&7Max tier reached (Master Sanctuary)"));
        } else {
            double cost = plugin.getConfig().getDouble("tiers." + nextTier.toLowerCase() + ".upgrade-cost-economy", 0);
            inv.setItem(25, createItem(Material.ANVIL, "&6⬆ Upgrade Waypoint",
                "&7Current tier: &f" + tier,
                "&7Next tier: &e" + nextTier,
                "&7Cost: &a$" + cost + " &7+ items",
                "",
                (nextTier.equals("ADVANCED") ? "&a✦ Unlocks Wayfarer's Grace (Tier I)!" : "&6✦ Upgrades to Master Divine Sanctuary!"),
                "&eClick to preview & confirm upgrade"));
        }

        // Row 4
        String graceDesc;
        if (wp.isGlobal()) {
            graceDesc = "&6Master Divine Sanctuary &7(Always Active)";
        } else if (tier.equalsIgnoreCase("MASTER")) {
            graceDesc = "&6Tier II Master Sanctuary &7(Active)";
        } else if (tier.equalsIgnoreCase("ADVANCED")) {
            graceDesc = "&aTier I Active &7(Resistance, Regen, Speed)";
        } else {
            graceDesc = "&cLocked &7(Upgrade waypoint to unlock!)";
        }

        inv.setItem(29, createItem(Material.LODESTONE, "&f✦ " + wp.getName(),
            "&7Owner: &f" + wp.getOwnerName(),
            "&7World: &f" + wp.getWorldName(),
            "&7Coords: &f" + (int)wp.getX() + ", " + (int)wp.getY() + ", " + (int)wp.getZ(),
            "&7Tier: &f" + tier,
            "&7Wayfarer's Grace: " + graceDesc,
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

        Material[] crystals = {
            Material.END_CRYSTAL, Material.AMETHYST_CLUSTER, Material.CONDUIT,
            Material.RESPAWN_ANCHOR, Material.BEACON, Material.SEA_LANTERN,
            Material.EMERALD_BLOCK, Material.DIAMOND_BLOCK, Material.GLOWSTONE
        };
        String[] crystalNames = {
            "&d✦ Ender Crystal", "&d✦ Amethyst Crystal", "&b✦ Conduit Core",
            "&5✦ Respawn Anchor", "&f✦ Crystal Beacon", "&3✦ Sea Lantern",
            "&a✦ Emerald Relic", "&b✦ Diamond Relic", "&e✦ Glowstone Crystal"
        };
        for(int i = 0; i < 9; i++) {
            ItemStack item = createItem(crystals[i], crystalNames[i], "&7Click to select crystal");
            String wpMat = wp.getCrystalMaterial();
            boolean isSelected = false;
            if (crystals[i] == Material.END_CRYSTAL) {
                isSelected = (wpMat == null || wpMat.equalsIgnoreCase("END_CRYSTAL") || wpMat.equalsIgnoreCase("AMETHYST_SHARD") || wpMat.equalsIgnoreCase("DEFAULT"));
            } else if (crystals[i].name().equalsIgnoreCase(wpMat)) {
                isSelected = true;
            }
            if (isSelected) {
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
        
        int maxDur = plugin.getConfigManager().getMaxDurabilityForTier(nextTier);
        List<String> bLore = new ArrayList<>();
        bLore.add("&7Max Outgoing Links: &f" + maxLinks);
        bLore.add("&7Max Trusted Allies: &f" + maxTrusted);
        bLore.add("&7Warp Speed Boost: &f" + speedMult + "x");
        bLore.add("&7Max Durability: &f" + maxDur + " pts");
        bLore.add("");
        if (nextTier.equals("ADVANCED")) {
            bLore.add("&a✦ Unlocks Wayfarer's Grace (Tier I)!");
            bLore.add("  &7Arriving travelers receive 6s of:");
            bLore.add("  &f- Resistance I &7(Damage Reduction)");
            bLore.add("  &f- Regeneration I &7(Health Recovery)");
            bLore.add("  &f- Speed I &7(Mobility)");
        } else if (nextTier.equals("MASTER")) {
            bLore.add("&6✦ Upgrades to Master Divine Sanctuary!");
            bLore.add("  &7Arriving travelers receive 10s of:");
            bLore.add("  &f- Resistance II &7(Empowered Protection)");
            bLore.add("  &f- Regeneration II &7(Rapid Healing)");
            bLore.add("  &f- Speed II &7(High Velocity)");
            bLore.add("  &f- Fire Resistance I &7(Lava/Fire Immune)");
            bLore.add("  &f- Absorption I &7(+2 Golden Hearts)");
        }
        inv.setItem(11, createItem(Material.EXPERIENCE_BOTTLE, "&b✦ Upgrade Benefits", bLore.toArray(new String[0])));
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
                            org.bukkit.Location dest = plugin.getTeleportManager().calculateSafeLandingLocation(targetWp);
                            if (dest == null) dest = targetWp.toBukkitLocation();
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
                // Category Filter clicks (Row 1)
                if (slot == 0) {
                    openTravelMenu(player, wp, 1, "ALL", holder.getSearchQuery());
                    return;
                }
                if (slot == 1) {
                    openTravelMenu(player, wp, 1, "FAVORITES", holder.getSearchQuery());
                    return;
                }
                if (slot == 2) {
                    openTravelMenu(player, wp, 1, "GLOBAL", holder.getSearchQuery());
                    return;
                }
                if (slot == 3) {
                    openTravelMenu(player, wp, 1, "LINKED", holder.getSearchQuery());
                    return;
                }
                if (slot == 5) {
                    openTravelMenu(player, wp, 1, "NEAREST", holder.getSearchQuery());
                    return;
                }
                if (slot == 6) { // Search
                    player.closeInventory();
                    startPendingSearch(player.getUniqueId(), wp.getId());
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &e✦ Type destination search keyword in chat &7(or 'clear' / 'cancel'):"));
                    return;
                }
                if (slot == 7) { // Clear search
                    if (holder.getSearchQuery() != null && !holder.getSearchQuery().isEmpty()) {
                        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &7Search filter cleared."));
                        openTravelMenu(player, wp, 1, holder.getFilter(), null);
                    }
                    return;
                }

                // Bottom row clicks (Row 6)
                if (slot == 45) {
                    if (holder.getPage() > 1) {
                        openTravelMenu(player, wp, holder.getPage() - 1, holder.getFilter(), holder.getSearchQuery());
                    } else if (wp.isOwner(player.getUniqueId())) {
                        openManagementMenu(player, wp);
                    }
                    return;
                }
                if (slot == 49) {
                    player.closeInventory();
                    return;
                }
                if (slot == 53 && clicked.getType() == Material.ARROW) {
                    openTravelMenu(player, wp, holder.getPage() + 1, holder.getFilter(), holder.getSearchQuery());
                    return;
                }

                // Destination item clicks (Slots 9 to 44)
                if (clicked.hasItemMeta()) {
                    String targetIdStr = clicked.getItemMeta().getPersistentDataContainer().get(KEY_TARGET_WP, PersistentDataType.STRING);
                    if (targetIdStr != null) {
                        try {
                            UUID targetId = UUID.fromString(targetIdStr);
                            Waypoint dest = plugin.getWaypointManager().getWaypoint(targetId);
                            if (dest != null) {
                                boolean canAccess = dest.isGlobal()
                                        || plugin.getWaypointManager().hasDiscovered(player.getUniqueId(), dest.getId())
                                        || dest.isOwner(player.getUniqueId())
                                        || player.hasPermission("tirnue.waypoints.admin");
                                if (!canAccess) {
                                    player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.7f);
                                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cYou must discover and attune to this waypoint in person before you can warp to it!"));
                                    return;
                                }

                                if (event.isShiftClick()) {
                                    plugin.getWaypointManager().toggleFavorite(player.getUniqueId(), dest.getId());
                                    openTravelMenu(player, wp, holder.getPage(), holder.getFilter(), holder.getSearchQuery());
                                    return;
                                }

                                if (plugin.getConfigManager().isDurabilityEnabled() && !dest.isGlobal() && dest.isDepleted()) {
                                    player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 0.6f);
                                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cDestination waypoint '" + dest.getName() + "' is structurally depleted and cannot receive warps until repaired!"));
                                    return;
                                }

                                player.closeInventory();
                                plugin.getTeleportManager().startTeleport(player, wp, dest);
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
                } else if (slot == 20) { // Inscribe Warp Scroll
                    boolean inscribed = false;
                    for (int i = 0; i < player.getInventory().getSize(); i++) {
                        ItemStack it = player.getInventory().getItem(i);
                        if (plugin.getScrollManager().isBlankScroll(it)) {
                            it.setAmount(it.getAmount() - 1);
                            player.getInventory().setItem(i, it);
                            inscribed = true;
                            break;
                        }
                    }
                    if (!inscribed) {
                        if (player.getInventory().containsAtLeast(new ItemStack(Material.ENDER_PEARL), 1)
                                && player.getInventory().containsAtLeast(new ItemStack(Material.PAPER), 1)) {
                            player.getInventory().removeItem(new ItemStack(Material.ENDER_PEARL, 1));
                            player.getInventory().removeItem(new ItemStack(Material.PAPER, 1));
                            inscribed = true;
                        }
                    }

                    if (inscribed) {
                        ItemStack scroll = plugin.getScrollManager().generateAttunedScroll(wp, 1);
                        if (player.getInventory().firstEmpty() == -1) {
                            player.getWorld().dropItemNaturally(player.getLocation(), scroll);
                        } else {
                            player.getInventory().addItem(scroll);
                        }
                        player.playSound(player.getLocation(), Sound.ITEM_LODESTONE_COMPASS_LOCK, 1.0f, 1.2f);
                        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.5f);
                        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &a✦ Inscribed a Waystone Warp Scroll for &e" + wp.getName() + "&a!"));
                    } else {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.7f);
                        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cYou need a Blank Waystone Scroll (or 1 Ender Pearl + 1 Paper) to inscribe a scroll!"));
                    }
                } else if (slot == 21) { // Guest Pass
                    ItemStack pass = plugin.getLinkManager().generateGuestPass(wp);
                    player.getInventory().addItem(pass);
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aGuest pass generated."));
                } else if (slot == 22) { // Durability Repair via Vault
                    if (!plugin.getConfigManager().isDurabilityEnabled()) return;
                    if (wp.isGlobal()) {
                        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aGlobal waypoints possess infinite structural integrity!"));
                        return;
                    }
                    int curDur = wp.getDurability();
                    int maxDur = wp.getMaxDurability();
                    if (curDur >= maxDur) {
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aThis waypoint is already at 100% integrity! No repairs needed."));
                        return;
                    }

                    int missing = maxDur - curDur;
                    double costPerPt = plugin.getConfigManager().getDurabilityCostPerPoint();
                    double totalCost = missing * costPerPt;

                    net.milkbowl.vault.economy.Economy econ = plugin.getEconomy();
                    if (econ != null && totalCost > 0) {
                        if (!econ.has(player, totalCost)) {
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.7f);
                            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cInsufficient funds to repair! You need &a$" + String.format("%.2f", totalCost) + "&c, but only have &a$" + String.format("%.2f", econ.getBalance(player)) + "&c."));
                            return;
                        }
                        econ.withdrawPlayer(player, totalCost);
                    }

                    wp.repairDurability(missing);
                    plugin.getWaypointManager().saveAsync();

                    player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.2f);
                    player.playSound(player.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 1.0f, 1.0f);
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &a✦ Repaired &e" + wp.getName() + " &ato 100% structural integrity! Cost: &a$" + String.format("%.2f", totalCost)));
                    openManagementMenu(player, wp);
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
                    Material[] crystals = {
                        Material.END_CRYSTAL, Material.AMETHYST_CLUSTER, Material.CONDUIT,
                        Material.RESPAWN_ANCHOR, Material.BEACON, Material.SEA_LANTERN,
                        Material.EMERALD_BLOCK, Material.DIAMOND_BLOCK, Material.GLOWSTONE
                    };
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
                    int newMaxDur = plugin.getConfigManager().getMaxDurabilityForTier(nextTier);
                    wp.setMaxDurability(newMaxDur);
                    wp.repairDurability(newMaxDur);
                    plugin.getWaypointManager().saveAsync();

                    player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.2f);
                    if (wp.toBukkitLocation() != null) {
                        player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, wp.toBukkitLocation().add(0, 1.5, 0), 40, 0.6, 0.8, 0.6, 0.2);
                    }

                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &a✦ Waypoint upgraded to &e" + nextTier.toUpperCase() + "&a!"));
                    if (nextTier.equalsIgnoreCase("ADVANCED")) {
                        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &a✦ &lWayfarer's Grace (Tier I) Unlocked! &fTravelers arriving here now receive Resistance, Regen & Speed buffs!"));
                    } else if (nextTier.equalsIgnoreCase("MASTER")) {
                        player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &6✦ &lWayfarer's Grace (Master Sanctuary) Unlocked! &fTravelers arriving here receive empowered divine buffs!"));
                    }
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
        pendingSearches.remove(playerId);
    }
}
