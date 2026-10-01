package com.tirnue.waypoints;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WaypointRenderer {
    private final TirnueWaypoints plugin;
    private final ConfigManager config;
    // Store direct entity references instead of UUIDs to avoid O(N) Bukkit.getEntity() lookups
    private final Map<UUID, WaypointEntities> spawnedEntities = new HashMap<>();
    private final Map<UUID, Waypoint> waypointDataCache = new HashMap<>();
    private final NamespacedKey wpIdKey;
    private BukkitTask animationTask;
    // Pre-allocate reusable Quaternionf to avoid GC pressure in animation loop
    private final Quaternionf reusableQuat = new Quaternionf();

    public WaypointRenderer(TirnueWaypoints plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.wpIdKey = new NamespacedKey(plugin, "tirnue_wp_id");
    }

    public void spawnWaypoint(Waypoint wp) {
        Location loc = wp.toBukkitLocation();
        if (loc == null || loc.getWorld() == null) return;
        // Don't spawn into unloaded chunks
        if (!loc.getWorld().isChunkLoaded(((int) loc.getX()) >> 4, ((int) loc.getZ()) >> 4)) return;
        // Don't double-spawn
        if (spawnedEntities.containsKey(wp.getId())) return;

        World world = loc.getWorld();

        // 1. BlockDisplay base
        BlockDisplay base = world.spawn(loc, BlockDisplay.class, entity -> {
            entity.setBlock(config.getBaseBlock().createBlockData());
            Transformation t = entity.getTransformation();
            t.getScale().set(0.8f, 0.8f, 0.8f);
            t.getTranslation().set(-0.4f, 0, -0.4f);
            entity.setTransformation(t);
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wp.getId().toString());
        });

        // 2. ItemDisplay crystal
        Location crystalLoc = loc.clone().add(0, 1.5, 0);
        org.bukkit.Material parsedMat;
        try {
            parsedMat = org.bukkit.Material.valueOf(wp.getCrystalMaterial());
        } catch (IllegalArgumentException e) {
            parsedMat = config.getCoreItemVisual();
        }
        final org.bukkit.Material crystalMat = parsedMat;
        ItemDisplay crystal = world.spawn(crystalLoc, ItemDisplay.class, entity -> {
            entity.setItemStack(new ItemStack(crystalMat));
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wp.getId().toString());
        });

        // 3. TextDisplay label
        Location labelLoc = loc.clone().add(0, 2.2, 0);
        TextDisplay label = world.spawn(labelLoc, TextDisplay.class, entity -> {
            String text = "\n&b\u2726 &l" + wp.getName() + "\n&7" + wp.getOwnerName() + "\n";
            entity.setText(ChatColor.translateAlternateColorCodes('&', text));
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setBillboard(TextDisplay.Billboard.CENTER);
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wp.getId().toString());
        });

        // 4. Interaction entity
        Interaction interaction = world.spawn(loc, Interaction.class, entity -> {
            entity.setInteractionWidth(1.5f);
            entity.setInteractionHeight(2.5f);
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wp.getId().toString());
        });

        // Store direct entity references — no more Bukkit.getEntity() lookups needed
        spawnedEntities.put(wp.getId(), new WaypointEntities(base, crystal, label, interaction));
        waypointDataCache.put(wp.getId(), wp);
    }

    public void despawnWaypoint(UUID waypointId) {
        WaypointEntities entities = spawnedEntities.remove(waypointId);
        waypointDataCache.remove(waypointId);
        if (entities != null) {
            safeRemove(entities.base);
            safeRemove(entities.crystal);
            safeRemove(entities.label);
            safeRemove(entities.interaction);
        }
    }

    private void safeRemove(Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    public void despawnAll() {
        for (WaypointEntities entities : spawnedEntities.values()) {
            safeRemove(entities.base);
            safeRemove(entities.crystal);
            safeRemove(entities.label);
            safeRemove(entities.interaction);
        }
        spawnedEntities.clear();
        waypointDataCache.clear();
    }

    public void updateLabel(UUID waypointId, String newName, String ownerName) {
        WaypointEntities entities = spawnedEntities.get(waypointId);
        if (entities != null && entities.label != null && entities.label.isValid()) {
            String text = "\n&b\u2726 &l" + newName + "\n&7" + ownerName + "\n";
            entities.label.setText(ChatColor.translateAlternateColorCodes('&', text));
        }
    }

    public void spawnAllWaypoints(Collection<Waypoint> waypoints) {
        for (Waypoint wp : waypoints) {
            spawnWaypoint(wp);
        }
    }

    public boolean isSpawned(UUID waypointId) {
        return spawnedEntities.containsKey(waypointId);
    }

    public void tickBeaconEffects() {
        if (spawnedEntities.isEmpty()) return;
        // Player-first iteration: O(P) outer loop instead of O(W*P)
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            for (Map.Entry<UUID, WaypointEntities> entry : spawnedEntities.entrySet()) {
                Waypoint wp = waypointDataCache.get(entry.getKey());
                if (wp == null) continue;
                if (!wp.isOwner(player.getUniqueId()) && !wp.isTrusted(player.getUniqueId())) continue;

                WaypointEntities entities = entry.getValue();
                if (entities.base == null || !entities.base.isValid()) continue;
                if (!entities.base.getWorld().equals(player.getWorld())) continue;

                Location baseLoc = entities.base.getLocation();
                if (player.getLocation().distanceSquared(baseLoc) > 2500) continue;

                // Spawn beacon beam particles (player-specific, no server broadcast)
                double bx = baseLoc.getX(), by = baseLoc.getY(), bz = baseLoc.getZ();
                for (int i = 0; i < 3; i++) {
                    player.spawnParticle(org.bukkit.Particle.SOUL_FIRE_FLAME,
                            bx + (Math.random() - 0.5) * 0.2,
                            by + 2.5 + Math.random() * 2.5,
                            bz + (Math.random() - 0.5) * 0.2,
                            1, 0, 0, 0, 0);
                }
            }
        }
    }

    public void startAnimationTask() {
        if (animationTask != null && !animationTask.isCancelled()) return;

        animationTask = new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                ticks += 2;
                if (ticks % 10 == 0) {
                    tickBeaconEffects();
                }
                float rotationAngle = (float) Math.toRadians((config.getCrystalRotationSpeed() * ticks) % 360);
                float bobOffset = (float) (Math.sin(Math.toRadians(ticks * 5)) * config.getCrystalBobAmplitude());
                boolean doParticles = config.isAmbientParticles() && (ticks % 6 == 0); // particles every 6 ticks instead of every 2

                // Snapshot to avoid ConcurrentModification if chunk unload removes entries
                List<Map.Entry<UUID, WaypointEntities>> snapshot = new ArrayList<>(spawnedEntities.entrySet());
                for (Map.Entry<UUID, WaypointEntities> entry : snapshot) {
                    WaypointEntities entities = entry.getValue();
                    UUID wpId = entry.getKey();
                    Waypoint wp = waypointDataCache.get(wpId);
                    
                    ItemDisplay crystal = entities.crystal;
                    if (crystal == null || !crystal.isValid()) continue;

                    // Direct entity reference — zero lookup cost
                    Transformation t = crystal.getTransformation();
                    reusableQuat.rotationY(rotationAngle);
                    t.getLeftRotation().set(reusableQuat);
                    t.getTranslation().set(0, bobOffset, 0);
                    crystal.setTransformation(t);

                    if (doParticles && wp != null) {
                        Location loc = crystal.getLocation();
                        loc.add(0, bobOffset, 0);
                        org.bukkit.Particle p;
                        try {
                            p = org.bukkit.Particle.valueOf(wp.getParticleType());
                        } catch (Exception e) {
                            p = config.getAmbientParticle();
                        }
                        ParticleUtil.spawn(loc.getWorld(), p, loc, 2, 0.2, 0.2, 0.2, 0.01);
                    }
                    
                    // Decaying visual: red particles if decaying
                    if (wp != null && wp.isDecaying()) {
                        Location decayLoc = crystal.getLocation();
                        decayLoc.getWorld().spawnParticle(org.bukkit.Particle.SMOKE, decayLoc, 3, 0.3, 0.3, 0.3, 0.01);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    public void stopAnimationTask() {
        if (animationTask != null) {
            animationTask.cancel();
            animationTask = null;
        }
    }

    public UUID getWaypointIdFromInteraction(Entity interactionEntity) {
        if (interactionEntity.getPersistentDataContainer().has(wpIdKey, PersistentDataType.STRING)) {
            try {
                return UUID.fromString(interactionEntity.getPersistentDataContainer().get(wpIdKey, PersistentDataType.STRING));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    public boolean isWaypointInteraction(Entity entity) {
        return entity instanceof Interaction && entity.getPersistentDataContainer().has(wpIdKey, PersistentDataType.STRING);
    }

    // Store direct entity references instead of UUIDs to eliminate Bukkit.getEntity() O(N) lookups
    private static class WaypointEntities {
        final BlockDisplay base;
        final ItemDisplay crystal;
        final TextDisplay label;
        final Interaction interaction;

        WaypointEntities(BlockDisplay base, ItemDisplay crystal, TextDisplay label, Interaction interaction) {
            this.base = base;
            this.crystal = crystal;
            this.label = label;
            this.interaction = interaction;
        }
    }
}
