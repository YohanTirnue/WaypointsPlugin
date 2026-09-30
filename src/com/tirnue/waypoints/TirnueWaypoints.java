package com.tirnue.waypoints;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class TirnueWaypoints extends JavaPlugin {
    private ConfigManager configManager;
    private WaypointManager waypointManager;
    private WaypointRenderer waypointRenderer;
    private TeleportManager teleportManager;
    private LinkManager linkManager;
    private WaypointGUI waypointGUI;
    private Economy economy;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        configManager = new ConfigManager(this);
        setupEconomy();
        
        waypointManager = new WaypointManager(this, configManager);
        waypointManager.load();
        
        linkManager = new LinkManager(this, waypointManager, configManager);
        linkManager.loadUsedTokens();
        
        waypointRenderer = new WaypointRenderer(this, configManager);
        teleportManager = new TeleportManager(this, configManager, waypointRenderer);
        waypointGUI = new WaypointGUI(this);
        
        WaypointCommand command = new WaypointCommand(this);
        getCommand("waypoint").setExecutor(command);
        getCommand("waypoint").setTabCompleter(command);
        
        getServer().getPluginManager().registerEvents(new WaypointListener(this), this);
        
        Bukkit.getScheduler().runTaskLater(this, () -> {
            waypointRenderer.spawnAllWaypoints(waypointManager.getAllWaypoints());
            waypointRenderer.startAnimationTask();
        }, 1L);
        
        getLogger().info("TirnueWaypoints enabled!");
    }

    @Override
    public void onDisable() {
        if (waypointRenderer != null) {
            waypointRenderer.stopAnimationTask();
            waypointRenderer.despawnAll();
        }
        if (waypointManager != null) {
            waypointManager.save();
        }
        if (linkManager != null) {
            linkManager.saveUsedTokens();
        }
        getLogger().info("TirnueWaypoints disabled!");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    public ConfigManager getConfigManager() { return configManager; }
    public WaypointManager getWaypointManager() { return waypointManager; }
    public WaypointRenderer getWaypointRenderer() { return waypointRenderer; }
    public TeleportManager getTeleportManager() { return teleportManager; }
    public LinkManager getLinkManager() { return linkManager; }
    public WaypointGUI getWaypointGUI() { return waypointGUI; }
    public Economy getEconomy() { return economy; }
}
