package com.tirnue.waypoints;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.HashSet;
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
}
