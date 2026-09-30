package com.tirnue.waypoints;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class LinkManager {

    private final JavaPlugin plugin;
    private final WaypointManager waypointManager;
    private final ConfigManager configManager;
    private final NamespacedKey KEY_LEDGER_MARKER;
    private final NamespacedKey KEY_LEDGER_WAYPOINT_ID;
    private final NamespacedKey KEY_LEDGER_TOKEN;

    private final Set<String> usedTokens = new HashSet<>();
    private final File tokensFile;

    public LinkManager(JavaPlugin plugin, WaypointManager waypointManager, ConfigManager configManager) {
        this.plugin = plugin;
        this.waypointManager = waypointManager;
        this.configManager = configManager;
        
        this.KEY_LEDGER_MARKER = new NamespacedKey(plugin, "tirnue_ledger");
        this.KEY_LEDGER_WAYPOINT_ID = new NamespacedKey(plugin, "tirnue_ledger_wp");
        this.KEY_LEDGER_TOKEN = new NamespacedKey(plugin, "tirnue_ledger_token");

        this.tokensFile = new File(plugin.getDataFolder(), "used_tokens.yml");
        loadUsedTokens();
    }

    public ItemStack generateLedger(Waypoint source) {
        ItemStack ledger = new ItemStack(configManager.getLedgerMaterial());
        ItemMeta meta = ledger.getItemMeta();
        if (meta == null) return ledger;

        meta.setDisplayName(configManager.getLedgerName());

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Attuned to: " + ChatColor.AQUA + ChatColor.translateAlternateColorCodes('&', source.getName()));
        lore.add(ChatColor.GRAY + "Owner: " + ChatColor.WHITE + source.getOwnerName());
        lore.add("");
        lore.add(ChatColor.YELLOW + "Use this at another waypoint");
        lore.add(ChatColor.YELLOW + "to create a permanent link.");
        meta.setLore(lore);

        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_LEDGER_MARKER, PersistentDataType.BYTE, (byte) 1);
        pdc.set(KEY_LEDGER_WAYPOINT_ID, PersistentDataType.STRING, source.getId().toString());
        
        String token = UUID.randomUUID().toString();
        pdc.set(KEY_LEDGER_TOKEN, PersistentDataType.STRING, token);

        ledger.setItemMeta(meta);
        return ledger;
    }

    public boolean isLedger(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(KEY_LEDGER_MARKER, PersistentDataType.BYTE);
    }

    public UUID getLedgerWaypointId(ItemStack item) {
        if (!isLedger(item)) return null;
        String idStr = item.getItemMeta().getPersistentDataContainer().get(KEY_LEDGER_WAYPOINT_ID, PersistentDataType.STRING);
        if (idStr != null) {
            try {
                return UUID.fromString(idStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    public String getLedgerToken(ItemStack item) {
        if (!isLedger(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(KEY_LEDGER_TOKEN, PersistentDataType.STRING);
    }

    public boolean isTokenUsed(String token) {
        return usedTokens.contains(token);
    }

    public boolean redeemLedger(Player redeemer, Waypoint destination, ItemStack ledger) {
        if (!isLedger(ledger)) return false;

        UUID sourceId = getLedgerWaypointId(ledger);
        String token = getLedgerToken(ledger);

        if (sourceId == null || token == null || isTokenUsed(token)) {
            return false;
        }

        Waypoint source = waypointManager.getWaypoint(sourceId);
        if (source == null) {
            return false; // Source waypoint no longer exists
        }

        if (source.getId().equals(destination.getId())) {
            return false; // Cannot link to itself
        }

        // Link the waypoints
        waypointManager.linkWaypoints(source.getId(), destination.getId());
        
        // Mark token as used and save
        usedTokens.add(token);
        saveUsedTokens();

        // Consume item
        ledger.setAmount(ledger.getAmount() - 1);

        return true;
    }

    public void saveUsedTokens() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("tokens", new ArrayList<>(usedTokens));
        try {
            yaml.save(tokensFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save used tokens to " + tokensFile.getName());
        }
    }

    public void loadUsedTokens() {
        if (!tokensFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(tokensFile);
        usedTokens.clear();
        usedTokens.addAll(yaml.getStringList("tokens"));
    }
}
