package com.tirnue.waypoints;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class Waypoint {

    private UUID id;
    private String name;
    private UUID ownerUUID;
    private String ownerName;
    private String worldName;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private boolean isGlobal;
    private Set<UUID> trustedPlayers;
    private Set<UUID> linkedWaypointIds;
    private long createdAt;
    private String particleType = "ENCHANT";
    private String crystalMaterial = "AMETHYST_SHARD";
    private String tier = "BASIC";
    private double usageFee = 0.0;
    private final List<ActivityEntry> activityLog = new ArrayList<>();

    public static class ActivityEntry {
        private final String playerName;
        private final UUID playerUUID;
        private final String action;
        private final long timestamp;
        private final String destinationName;

        public ActivityEntry(String playerName, UUID playerUUID, String action, long timestamp, String destinationName) {
            this.playerName = playerName;
            this.playerUUID = playerUUID;
            this.action = action;
            this.timestamp = timestamp;
            this.destinationName = destinationName;
        }

        public String getPlayerName() { return playerName; }
        public UUID getPlayerUUID() { return playerUUID; }
        public String getAction() { return action; }
        public long getTimestamp() { return timestamp; }
        public String getDestinationName() { return destinationName; }

        public String getFormattedTime() {
            long diff = System.currentTimeMillis() - timestamp;
            if (diff < 60000) return (diff / 1000) + "s ago";
            if (diff < 3600000) return (diff / 60000) + "m ago";
            if (diff < 86400000) return (diff / 3600000) + "h ago";
            return (diff / 86400000) + "d ago";
        }
    }

    public Waypoint(UUID id, String name, UUID ownerUUID, String ownerName, String worldName, double x, double y, double z, float yaw, float pitch, boolean isGlobal, Set<UUID> trustedPlayers, Set<UUID> linkedWaypointIds, long createdAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.name = name;
        this.ownerUUID = ownerUUID;
        this.ownerName = ownerName;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.isGlobal = isGlobal;
        this.trustedPlayers = trustedPlayers != null ? trustedPlayers : new HashSet<>();
        this.linkedWaypointIds = linkedWaypointIds != null ? linkedWaypointIds : new HashSet<>();
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public UUID getOwnerUUID() { return ownerUUID; }
    public void setOwnerUUID(UUID ownerUUID) { this.ownerUUID = ownerUUID; }
    
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    
    public String getWorldName() { return worldName; }
    public void setWorldName(String worldName) { this.worldName = worldName; }
    
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    
    public double getZ() { return z; }
    public void setZ(double z) { this.z = z; }
    
    public float getYaw() { return yaw; }
    public void setYaw(float yaw) { this.yaw = yaw; }
    
    public float getPitch() { return pitch; }
    public void setPitch(float pitch) { this.pitch = pitch; }
    
    public boolean isGlobal() { return isGlobal; }
    public void setGlobal(boolean global) { isGlobal = global; }
    
    public Set<UUID> getTrustedPlayers() { return trustedPlayers; }
    public void setTrustedPlayers(Set<UUID> trustedPlayers) { this.trustedPlayers = trustedPlayers; }
    
    public Set<UUID> getLinkedWaypointIds() { return linkedWaypointIds; }
    public void setLinkedWaypointIds(Set<UUID> linkedWaypointIds) { this.linkedWaypointIds = linkedWaypointIds; }
    
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public String getParticleType() { return particleType; }
    public void setParticleType(String particleType) { this.particleType = particleType; }

    public String getCrystalMaterial() { return crystalMaterial; }
    public void setCrystalMaterial(String crystalMaterial) { this.crystalMaterial = crystalMaterial; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public double getUsageFee() { return usageFee; }
    public void setUsageFee(double usageFee) { this.usageFee = usageFee; }

    private boolean decaying = false;
    private long decayStartTime = 0;

    public boolean isDecaying() { return decaying; }
    public void setDecaying(boolean decaying) { this.decaying = decaying; }

    public long getDecayStartTime() { return decayStartTime; }
    public void setDecayStartTime(long decayStartTime) { this.decayStartTime = decayStartTime; }

    public boolean isOwner(UUID playerUUID) {
        return ownerUUID != null && ownerUUID.equals(playerUUID);
    }

    public boolean isTrusted(UUID playerUUID) {
        return isOwner(playerUUID) || trustedPlayers.contains(playerUUID);
    }

    public void addTrust(UUID playerUUID) {
        trustedPlayers.add(playerUUID);
    }

    public void removeTrust(UUID playerUUID) {
        trustedPlayers.remove(playerUUID);
    }

    public void addLink(UUID waypointId) {
        linkedWaypointIds.add(waypointId);
    }

    public void removeLink(UUID waypointId) {
        linkedWaypointIds.remove(waypointId);
    }

    public boolean isLinkedTo(UUID waypointId) {
        return linkedWaypointIds.contains(waypointId);
    }

    public Location toBukkitLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, x, y, z, yaw, pitch);
    }

    public boolean isSameDimension(Waypoint other) {
        return this.worldName != null && this.worldName.equals(other.worldName);
    }

    public double distanceTo(Waypoint other) {
        if (!isSameDimension(other)) {
            return -1;
        }
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        double dz = this.z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public void addActivity(String playerName, UUID playerUUID, String action, String destinationName) {
        activityLog.add(0, new ActivityEntry(playerName, playerUUID, action, System.currentTimeMillis(), destinationName));
        if (activityLog.size() > 20) {
            activityLog.remove(activityLog.size() - 1);
        }
    }

    public List<ActivityEntry> getActivityLog() {
        return activityLog;
    }
}
