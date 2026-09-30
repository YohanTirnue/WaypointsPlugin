package com.tirnue.waypoints;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class WaypointManager {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final Map<UUID, Waypoint> waypoints = new HashMap<>();
    private final File dataFile;
    private final Map<UUID, Set<UUID>> playerFavorites = new HashMap<>();
    private final File favoritesFile;

    public WaypointManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.dataFile = new File(plugin.getDataFolder(), "waypoints.yml");
        this.favoritesFile = new File(plugin.getDataFolder(), "favorites.yml");
    }

    public Waypoint createWaypoint(Player owner, String name, Location loc) {
        Waypoint waypoint = new Waypoint(
                UUID.randomUUID(),
                name,
                owner.getUniqueId(),
                owner.getName(),
                loc.getWorld().getName(),
                loc.getX(),
                loc.getY(),
                loc.getZ(),
                loc.getYaw(),
                loc.getPitch(),
                false,
                new HashSet<>(),
                new HashSet<>(),
                System.currentTimeMillis()
        );
        waypoints.put(waypoint.getId(), waypoint);
        saveAsync();
        return waypoint;
    }

    public void deleteWaypoint(UUID waypointId) {
        Waypoint removed = waypoints.remove(waypointId);
        if (removed != null) {
            // Despawn display entities to prevent entity leak
            if (plugin instanceof TirnueWaypoints tw && tw.getWaypointRenderer() != null) {
                tw.getWaypointRenderer().despawnWaypoint(waypointId);
            }
            // Sever all links from other waypoints pointing to this one
            for (Waypoint w : waypoints.values()) {
                w.getLinkedWaypointIds().remove(waypointId);
            }
            saveAsync();
        }
    }

    public Waypoint getWaypoint(UUID id) {
        return waypoints.get(id);
    }

    public List<Waypoint> getWaypointsByOwner(UUID ownerUUID) {
        List<Waypoint> list = new ArrayList<>();
        for (Waypoint w : waypoints.values()) {
            if (w.isOwner(ownerUUID)) {
                list.add(w);
            }
        }
        return list;
    }

    public List<Waypoint> getGlobalWaypoints() {
        List<Waypoint> list = new ArrayList<>();
        for (Waypoint w : waypoints.values()) {
            if (w.isGlobal()) {
                list.add(w);
            }
        }
        return list;
    }

    public Waypoint getWaypointNear(Location loc, double radius) {
        Waypoint closest = null;
        double minDistanceSq = radius * radius;
        String worldName = loc.getWorld().getName();

        for (Waypoint w : waypoints.values()) {
            if (!w.getWorldName().equals(worldName)) continue;

            double dx = w.getX() - loc.getX();
            double dy = w.getY() - loc.getY();
            double dz = w.getZ() - loc.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq <= minDistanceSq) {
                closest = w;
                minDistanceSq = distSq;
            }
        }
        return closest;
    }

    public int getWaypointCount(UUID ownerUUID) {
        return getWaypointsByOwner(ownerUUID).size();
    }

    public void linkWaypoints(UUID wpA, UUID wpB) {
        Waypoint a = getWaypoint(wpA);
        Waypoint b = getWaypoint(wpB);
        if (a != null && b != null) {
            a.addLink(wpB);
            b.addLink(wpA);
            saveAsync();
        }
    }

    public void unlinkWaypoints(UUID wpA, UUID wpB) {
        Waypoint a = getWaypoint(wpA);
        Waypoint b = getWaypoint(wpB);
        if (a != null) a.removeLink(wpB);
        if (b != null) b.removeLink(wpA);
        saveAsync();
    }

    public void trustPlayer(UUID waypointId, UUID playerUUID) {
        Waypoint wp = getWaypoint(waypointId);
        if (wp != null) {
            wp.addTrust(playerUUID);
            saveAsync();
        }
    }

    public void untrustPlayer(UUID waypointId, UUID playerUUID) {
        Waypoint wp = getWaypoint(waypointId);
        if (wp != null) {
            wp.removeTrust(playerUUID);
            saveAsync();
        }
    }

    public Collection<Waypoint> getAllWaypoints() {
        return waypoints.values();
    }

    public void save() {
        YamlConfiguration yaml = buildYaml();
        try {
            yaml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save waypoints to " + dataFile.getName());
        }
    }

    /**
     * Async save: builds YAML snapshot on main thread, writes to disk off-thread.
     * Used for all in-game mutations to avoid TPS drops from disk I/O.
     */
    public void saveAsync() {
        YamlConfiguration yaml = buildYaml();
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                yaml.save(dataFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save waypoints to " + dataFile.getName());
            }
        });
    }

    private YamlConfiguration buildYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Waypoint wp : waypoints.values()) {
            String key = wp.getId().toString();
            yaml.set(key + ".name", wp.getName());
            yaml.set(key + ".ownerUUID", wp.getOwnerUUID().toString());
            yaml.set(key + ".ownerName", wp.getOwnerName());
            yaml.set(key + ".worldName", wp.getWorldName());
            yaml.set(key + ".x", wp.getX());
            yaml.set(key + ".y", wp.getY());
            yaml.set(key + ".z", wp.getZ());
            yaml.set(key + ".yaw", wp.getYaw());
            yaml.set(key + ".pitch", wp.getPitch());
            yaml.set(key + ".isGlobal", wp.isGlobal());
            List<String> trusted = new ArrayList<>();
            for (UUID u : wp.getTrustedPlayers()) trusted.add(u.toString());
            yaml.set(key + ".trustedPlayers", trusted);
            List<String> linked = new ArrayList<>();
            for (UUID u : wp.getLinkedWaypointIds()) linked.add(u.toString());
            yaml.set(key + ".linkedWaypointIds", linked);
            yaml.set(key + ".createdAt", wp.getCreatedAt());
            
            List<Waypoint.ActivityEntry> activities = wp.getActivityLog();
            for (int i = 0; i < activities.size() && i < 20; i++) {
                Waypoint.ActivityEntry entry = activities.get(i);
                String actKey = key + ".activityLog." + i;
                yaml.set(actKey + ".playerName", entry.getPlayerName());
                yaml.set(actKey + ".playerUUID", entry.getPlayerUUID().toString());
                yaml.set(actKey + ".action", entry.getAction());
                yaml.set(actKey + ".timestamp", entry.getTimestamp());
                yaml.set(actKey + ".destinationName", entry.getDestinationName());
            }
        }
        return yaml;
    }

    public void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        waypoints.clear();

        for (String key : yaml.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String name = yaml.getString(key + ".name", "Unknown Waypoint");
                UUID ownerUUID = UUID.fromString(yaml.getString(key + ".ownerUUID"));
                String ownerName = yaml.getString(key + ".ownerName", "Unknown");
                String worldName = yaml.getString(key + ".worldName", "world");
                double x = yaml.getDouble(key + ".x", 0.0);
                double y = yaml.getDouble(key + ".y", 0.0);
                double z = yaml.getDouble(key + ".z", 0.0);
                float yaw = (float) yaml.getDouble(key + ".yaw", 0.0);
                float pitch = (float) yaml.getDouble(key + ".pitch", 0.0);
                boolean isGlobal = yaml.getBoolean(key + ".isGlobal", false);
                
                Set<UUID> trustedPlayers = new HashSet<>();
                for (String uStr : yaml.getStringList(key + ".trustedPlayers")) {
                    trustedPlayers.add(UUID.fromString(uStr));
                }

                Set<UUID> linkedWaypointIds = new HashSet<>();
                for (String uStr : yaml.getStringList(key + ".linkedWaypointIds")) {
                    linkedWaypointIds.add(UUID.fromString(uStr));
                }

                long createdAt = yaml.getLong(key + ".createdAt", System.currentTimeMillis());

                Waypoint wp = new Waypoint(id, name, ownerUUID, ownerName, worldName, x, y, z, yaw, pitch, isGlobal, trustedPlayers, linkedWaypointIds, createdAt);
                
                if (yaml.contains(key + ".activityLog")) {
                    for (String iStr : yaml.getConfigurationSection(key + ".activityLog").getKeys(false)) {
                        String actKey = key + ".activityLog." + iStr;
                        String pName = yaml.getString(actKey + ".playerName");
                        UUID pUUID = UUID.fromString(yaml.getString(actKey + ".playerUUID"));
                        String act = yaml.getString(actKey + ".action");
                        long ts = yaml.getLong(actKey + ".timestamp");
                        String dest = yaml.getString(actKey + ".destinationName");
                        wp.getActivityLog().add(new Waypoint.ActivityEntry(pName, pUUID, act, ts, dest));
                    }
                }
                waypoints.put(id, wp);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load waypoint with key " + key + ": " + e.getMessage());
            }
        }
        loadFavorites();
    }

    public void toggleFavorite(UUID playerUUID, UUID waypointId) {
        Set<UUID> favs = playerFavorites.computeIfAbsent(playerUUID, k -> new HashSet<>());
        if (favs.contains(waypointId)) {
            favs.remove(waypointId);
        } else {
            favs.add(waypointId);
        }
        saveFavorites();
    }

    public boolean isFavorite(UUID playerUUID, UUID waypointId) {
        return playerFavorites.getOrDefault(playerUUID, Collections.emptySet()).contains(waypointId);
    }

    public Set<UUID> getFavorites(UUID playerUUID) {
        return playerFavorites.getOrDefault(playerUUID, Collections.emptySet());
    }

    public void saveFavorites() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Set<UUID>> entry : playerFavorites.entrySet()) {
            List<String> list = new ArrayList<>();
            for (UUID w : entry.getValue()) list.add(w.toString());
            yaml.set(entry.getKey().toString(), list);
        }
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                yaml.save(favoritesFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save favorites to " + favoritesFile.getName());
            }
        });
    }

    public void loadFavorites() {
        if (!favoritesFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(favoritesFile);
        playerFavorites.clear();
        for (String key : yaml.getKeys(false)) {
            try {
                UUID playerUUID = UUID.fromString(key);
                Set<UUID> favs = new HashSet<>();
                for (String wStr : yaml.getStringList(key)) {
                    favs.add(UUID.fromString(wStr));
                }
                playerFavorites.put(playerUUID, favs);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load favorites for " + key);
            }
        }
    }
}
