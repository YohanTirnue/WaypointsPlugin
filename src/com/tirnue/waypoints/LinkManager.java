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

    private final TirnueWaypoints plugin;
    private final WaypointManager waypointManager;
    private final ConfigManager configManager;
    private final NamespacedKey KEY_LEDGER_MARKER;
    private final NamespacedKey KEY_LEDGER_WAYPOINT_ID;
    private final NamespacedKey KEY_LEDGER_TOKEN;
    private final NamespacedKey KEY_GUEST_PASS_MARKER;
    private final NamespacedKey KEY_GUEST_PASS_WP_ID;
    private final NamespacedKey KEY_GUEST_PASS_TOKEN;

    private final Set<String> usedTokens = new HashSet<>();
    private final File tokensFile;

    public LinkManager(TirnueWaypoints plugin, WaypointManager waypointManager, ConfigManager configManager) {
        this.plugin = plugin;
        this.waypointManager = waypointManager;
        this.configManager = configManager;
        
        this.KEY_LEDGER_MARKER = new NamespacedKey(plugin, "tirnue_ledger");
        this.KEY_LEDGER_WAYPOINT_ID = new NamespacedKey(plugin, "tirnue_ledger_wp");
        this.KEY_LEDGER_TOKEN = new NamespacedKey(plugin, "tirnue_ledger_token");

        this.KEY_GUEST_PASS_MARKER = new NamespacedKey(plugin, "tirnue_guest_pass");
        this.KEY_GUEST_PASS_WP_ID = new NamespacedKey(plugin, "tirnue_guest_wp");
        this.KEY_GUEST_PASS_TOKEN = new NamespacedKey(plugin, "tirnue_guest_token");

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

        // World link restriction check
        if (plugin.getConfig().getBoolean("world-restrictions.enabled", false)) {
            String worldA = source.getWorldName();
            String worldB = destination.getWorldName();
            List<String> blocked = plugin.getConfig().getStringList("world-restrictions.blocked-pairs");
            for (String pair : blocked) {
                String[] parts = pair.split(":");
                if (parts.length == 2) {
                    if ((worldA.equalsIgnoreCase(parts[0]) && worldB.equalsIgnoreCase(parts[1])) ||
                        (worldA.equalsIgnoreCase(parts[1]) && worldB.equalsIgnoreCase(parts[0]))) {
                        redeemer.sendMessage(configManager.getPrefix() + org.bukkit.ChatColor.translateAlternateColorCodes('&', "&cThese worlds cannot be linked!"));
                        return false;
                    }
                }
            }
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

    public ItemStack generateGuestPass(Waypoint source) {
        Material mat = Material.valueOf(plugin.getConfig().getString("guest-pass.material", "PAPER").toUpperCase());
        ItemStack pass = new ItemStack(mat);
        ItemMeta meta = pass.getItemMeta();
        if (meta == null) return pass;

        String name = ChatColor.translateAlternateColorCodes('&', plugin.getConfig().getString("guest-pass.name", "&e✦ Waypoint Guest Pass"));
        meta.setDisplayName(name);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Attuned to: " + ChatColor.AQUA + ChatColor.translateAlternateColorCodes('&', source.getName()));
        lore.add(ChatColor.GRAY + "Owner: " + ChatColor.WHITE + source.getOwnerName());
        lore.add("");
        lore.add(ChatColor.YELLOW + "Single-use teleport");
        meta.setLore(lore);

        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_GUEST_PASS_MARKER, PersistentDataType.BYTE, (byte) 1);
        pdc.set(KEY_GUEST_PASS_WP_ID, PersistentDataType.STRING, source.getId().toString());
        
        String token = UUID.randomUUID().toString();
        pdc.set(KEY_GUEST_PASS_TOKEN, PersistentDataType.STRING, token);

        pass.setItemMeta(meta);
        return pass;
    }

    public boolean isGuestPass(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(KEY_GUEST_PASS_MARKER, PersistentDataType.BYTE);
    }

    public UUID getGuestPassWaypointId(ItemStack item) {
        if (!isGuestPass(item)) return null;
        String idStr = item.getItemMeta().getPersistentDataContainer().get(KEY_GUEST_PASS_WP_ID, PersistentDataType.STRING);
        if (idStr != null) {
            try {
                return UUID.fromString(idStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    public String getGuestPassToken(ItemStack item) {
        if (!isGuestPass(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(KEY_GUEST_PASS_TOKEN, PersistentDataType.STRING);
    }

    public boolean redeemGuestPass(Player redeemer, ItemStack pass) {
        if (!isGuestPass(pass)) return false;

        UUID sourceId = getGuestPassWaypointId(pass);
        String token = getGuestPassToken(pass);

        if (sourceId == null || token == null || isTokenUsed(token)) {
            String msg = configManager.getMessage("guest-pass-invalid");
            if (msg != null && !msg.isEmpty()) redeemer.sendMessage(configManager.getPrefix() + msg);
            return false;
        }

        Waypoint source = waypointManager.getWaypoint(sourceId);
        if (source == null) {
            String msg = configManager.getMessage("guest-pass-invalid");
            if (msg != null && !msg.isEmpty()) redeemer.sendMessage(configManager.getPrefix() + msg);
            return false;
        }

        usedTokens.add(token);
        saveUsedTokens();
        
        pass.setAmount(pass.getAmount() - 1);
        
        String msg = configManager.getMessage("guest-pass-used");
        if (msg != null && !msg.isEmpty()) redeemer.sendMessage(configManager.getPrefix() + msg);
        
        // Departure FX
        ParticleUtil.spawn(redeemer.getWorld(), org.bukkit.Particle.PORTAL, redeemer.getLocation().add(0, 1, 0), 30, 0.5, 1, 0.5, 0.1);
        redeemer.getWorld().playSound(redeemer.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);

        org.bukkit.Location dest = plugin.getTeleportManager().calculateSafeLandingLocation(source);
        if (dest == null) dest = source.toBukkitLocation();
        if (dest != null) {
            redeemer.teleport(dest);
            // Arrival BOOM FX
            org.bukkit.World destWorld = dest.getWorld();
            if (destWorld != null) {
                try {
                    destWorld.playSound(dest, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.85f, 1.5f);
                    destWorld.playSound(dest, org.bukkit.Sound.ENTITY_PLAYER_TELEPORT, 1.0f, 1.0f);
                    destWorld.playSound(dest, org.bukkit.Sound.ITEM_TOTEM_USE, 0.6f, 1.6f);
                    destWorld.spawnParticle(org.bukkit.Particle.EXPLOSION, dest.clone().add(0, 1.0, 0), 2, 0.3, 0.3, 0.3, 0);
                    destWorld.spawnParticle(org.bukkit.Particle.FLASH, dest.clone().add(0, 1.0, 0), 1, 0, 0, 0, 0);
                } catch (Throwable ignored) {}
                ParticleUtil.spawn(destWorld, org.bukkit.Particle.PORTAL, dest.getX(), dest.getY() + 1.0, dest.getZ(), 45, 0.8, 0.8, 0.8, 0.3);
                ParticleUtil.spawn(destWorld, org.bukkit.Particle.FIREWORK, dest.getX(), dest.getY() + 1.0, dest.getZ(), 30, 0.5, 0.6, 0.5, 0.15);
                ParticleUtil.spawn(destWorld, org.bukkit.Particle.END_ROD, dest.getX(), dest.getY() + 1.0, dest.getZ(), 20, 0.6, 0.7, 0.6, 0.2);
            }
        }

        return true;
    }


    public void saveUsedTokens() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("tokens", new ArrayList<>(usedTokens));
        // Async write to avoid TPS drops
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                yaml.save(tokensFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save used tokens to " + tokensFile.getName());
            }
        });
    }

    /** Synchronous save for use during onDisable (scheduler unavailable). */
    public void saveUsedTokensSync() {
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
