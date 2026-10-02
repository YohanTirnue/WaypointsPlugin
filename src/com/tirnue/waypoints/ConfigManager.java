package com.tirnue.waypoints;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.stream.Collectors;

public class ConfigManager {

    private final JavaPlugin plugin;
    private FileConfiguration config;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    public void reloadConfig() {
        loadConfig();
    }

    // --- Teleport ---
    public double getBaseWarmupSeconds() {
        return config.getDouble("teleport.base-warmup-seconds", 3.0);
    }

    public double getSecondsPer100Blocks() {
        return config.getDouble("teleport.seconds-per-100-blocks", 1.0);
    }

    public double getMaxWarmupSeconds() {
        return config.getDouble("teleport.max-warmup-seconds", 15.0);
    }

    public double getCrossDimensionSeconds() {
        return config.getDouble("teleport.cross-dimension-seconds", 12.0);
    }

    public boolean isCancelOnMove() {
        return config.getBoolean("teleport.cancel-on-move", true);
    }

    public double getMoveThreshold() {
        return config.getDouble("teleport.move-threshold", 0.5);
    }

    public boolean isCancelOnDamage() {
        return config.getBoolean("teleport.cancel-on-damage", true);
    }

    public int getCooldownSeconds() {
        return config.getInt("teleport.cooldown-seconds", 60);
    }

    public double getVipCooldownMultiplier() {
        return config.getDouble("teleport.vip-cooldown-multiplier", 0.5);
    }

    // --- Cost ---
    public boolean isCostEnabled() {
        return config.getBoolean("cost.enabled", false);
    }

    public String getCostType() {
        return config.getString("cost.type", "ECONOMY");
    }

    public double getEconomyBaseCost() {
        return config.getDouble("cost.economy-base-cost", 0.0);
    }

    public double getEconomyPer100Blocks() {
        return config.getDouble("cost.economy-per-100-blocks", 5.0);
    }

    public double getEconomyMaxCost() {
        return config.getDouble("cost.economy-max-cost", 500.0);
    }

    public int getXpLevelsPerWarp() {
        return config.getInt("cost.xp-levels-per-warp", 1);
    }

    public Material getCostItemType() {
        String mat = config.getString("cost.item-type", "ENDER_PEARL");
        try {
            return Material.valueOf(mat.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Material.ENDER_PEARL;
        }
    }

    public int getCostItemAmount() {
        return config.getInt("cost.item-amount", 1);
    }

    public boolean isGlobalWaypointsFree() {
        return config.getBoolean("cost.global-waypoints-free", true);
    }

    // --- Limits ---
    public int getDefaultMaxWaypoints() {
        return config.getInt("limits.default-max-waypoints", 2);
    }

    public int getDefaultMaxLinks() {
        return config.getInt("limits.default-max-links", 3);
    }

    public int getVipMaxWaypoints() {
        return config.getInt("limits.vip-max-waypoints", 5);
    }

    public int getVipMaxLinks() {
        return config.getInt("limits.vip-max-links", 6);
    }

    // --- Core Item ---
    public Material getCoreItemMaterial() {
        String mat = config.getString("core-item.material", "LODESTONE");
        try {
            return Material.valueOf(mat.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Material.LODESTONE;
        }
    }

    public String getCoreItemName() {
        return c(config.getString("core-item.name", "&d✦ Waypoint Core"));
    }

    public List<String> getCoreItemLore() {
        return config.getStringList("core-item.lore").stream()
                .map(this::c)
                .collect(Collectors.toList());
    }

    // --- Visuals ---
    public Material getBaseBlock() {
        String mat = config.getString("visuals.base-block", "CRYING_OBSIDIAN");
        try {
            return Material.valueOf(mat.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Material.CRYING_OBSIDIAN;
        }
    }

    public Material getCoreItemVisual() {
        String mat = config.getString("visuals.core-item", "AMETHYST_SHARD");
        try {
            return Material.valueOf(mat.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Material.AMETHYST_SHARD;
        }
    }

    public double getCrystalRotationSpeed() {
        return config.getDouble("visuals.crystal-rotation-speed", 2.0);
    }

    public double getCrystalBobAmplitude() {
        return config.getDouble("visuals.crystal-bob-amplitude", 0.15);
    }

    public boolean isAmbientParticles() {
        return config.getBoolean("visuals.ambient-particles", true);
    }

    public Particle getAmbientParticle() {
        return parseParticle(config.getString("visuals.ambient-particle", "ENCHANT"), Particle.ENCHANT);
    }

    public boolean isProximitySound() {
        return config.getBoolean("visuals.proximity-sound", true);
    }

    public int getProximityRadius() {
        return config.getInt("visuals.proximity-radius", 15);
    }

    // --- Teleport FX ---
    public Particle getChannelingParticle() {
        return parseParticle(config.getString("teleport-fx.channeling-particle", "PORTAL"), Particle.PORTAL);
    }

    public Particle getDepartureParticle() {
        return parseParticle(config.getString("teleport-fx.departure-particle", "FLASH"), Particle.FLASH);
    }

    public Particle getArrivalParticle() {
        return parseParticle(config.getString("teleport-fx.arrival-particle", "REVERSE_PORTAL"), Particle.REVERSE_PORTAL);
    }

    public Sound getChannelingSound() {
        return parseSound(config.getString("teleport-fx.channeling-sound", "BLOCK_BEACON_AMBIENT"), Sound.BLOCK_BEACON_AMBIENT);
    }

    public Sound getDepartureSound() {
        return parseSound(config.getString("teleport-fx.departure-sound", "ENTITY_ENDERMAN_TELEPORT"), Sound.ENTITY_ENDERMAN_TELEPORT);
    }

    public Sound getArrivalSound() {
        return parseSound(config.getString("teleport-fx.arrival-sound", "BLOCK_RESPAWN_ANCHOR_SET_SPAWN"), Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN);
    }

    // --- Ledger ---
    public Material getLedgerMaterial() {
        String mat = config.getString("ledger.material", "WRITTEN_BOOK");
        try {
            return Material.valueOf(mat.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Material.WRITTEN_BOOK;
        }
    }

    public String getLedgerName() {
        return c(config.getString("ledger.name", "&d✦ Waypoint Attunement Ledger"));
    }

    // --- Messages ---
    public String getPrefix() {
        return c(config.getString("messages.prefix", "&8[&b✦&8] "));
    }

    public String getMessage(String key) {
        String msg = config.getString("messages." + key, "");
        return c(msg);
    }

    // --- Scrolls ---
    public boolean isScrollCraftingEnabled() {
        return config.getBoolean("scrolls.crafting-enabled", true);
    }

    public double getScrollWarmupSeconds() {
        return config.getDouble("scrolls.warmup-seconds", 4.0);
    }

    public String getBlankScrollName() {
        return config.getString("scrolls.blank-name", "&b✦ Blank Waystone Scroll");
    }

    public String getAttunedScrollNameFormat() {
        return config.getString("scrolls.attuned-name", "&d✦ Waystone Scroll: &f{destination}");
    }

    // --- Durability ---
    public boolean isDurabilityEnabled() {
        return config.getBoolean("durability.enabled", true);
    }

    public int getDurabilityLossPerTeleport() {
        return config.getInt("durability.loss-per-teleport", 1);
    }

    public double getDurabilityCostPerPoint() {
        return config.getDouble("durability.vault-cost-per-point", 5.0);
    }

    public int getMaxDurabilityForTier(String tier) {
        if (tier == null) return 100;
        return config.getInt("durability.max-durability." + tier.toLowerCase(), 100);
    }

    // --- Helpers ---
    private String c(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    @SuppressWarnings("deprecation")
    private Particle parseParticle(String name, Particle fallback) {
        if (name == null) return fallback;
        try {
            return Particle.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @SuppressWarnings("deprecation")
    private Sound parseSound(String name, Sound fallback) {
        if (name == null) return fallback;
        try {
            return Sound.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
