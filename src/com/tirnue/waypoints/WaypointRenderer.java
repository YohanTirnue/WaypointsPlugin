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

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class WaypointRenderer {
    private final TirnueWaypoints plugin;
    private final ConfigManager config;
    private final Map<UUID, WaypointEntities> spawnedEntities = new HashMap<>();
    private final NamespacedKey wpIdKey;
    private BukkitTask animationTask;

    public WaypointRenderer(TirnueWaypoints plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.wpIdKey = new NamespacedKey(plugin, "tirnue_wp_id");
    }

    public void spawnWaypoint(Waypoint wp) {
        Location loc = wp.toBukkitLocation();
        if (loc == null || loc.getWorld() == null) return;
        World world = loc.getWorld();

        // 1. BlockDisplay base
        BlockDisplay base = world.spawn(loc, BlockDisplay.class, entity -> {
            entity.setBlock(config.getBaseBlock().createBlockData());
            Transformation t = entity.getTransformation();
            t.getScale().set(0.8f, 0.8f, 0.8f);
            t.getTranslation().set(-0.4f, 0, -0.4f);
            entity.setTransformation(t);
            entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wp.getId().toString());
        });

        // 2. ItemDisplay crystal
        Location crystalLoc = loc.clone().add(0, 1.5, 0);
        ItemDisplay crystal = world.spawn(crystalLoc, ItemDisplay.class, entity -> {
            entity.setItemStack(new ItemStack(config.getCoreItemVisual()));
            entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wp.getId().toString());
        });

        // 3. TextDisplay label
        Location labelLoc = loc.clone().add(0, 2.2, 0);
        TextDisplay label = world.spawn(labelLoc, TextDisplay.class, entity -> {
            String text = "\n&b\u2726 &l" + wp.getName() + "\n&7" + wp.getOwnerName() + "\n";
            entity.setText(ChatColor.translateAlternateColorCodes('&', text));
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setBillboard(TextDisplay.Billboard.CENTER);
            entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wp.getId().toString());
        });

        // 4. Interaction entity
        Interaction interaction = world.spawn(loc, Interaction.class, entity -> {
            entity.setInteractionWidth(1.5f);
            entity.setInteractionHeight(2.5f);
            entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wp.getId().toString());
        });

        spawnedEntities.put(wp.getId(), new WaypointEntities(base.getUniqueId(), crystal.getUniqueId(), label.getUniqueId(), interaction.getUniqueId()));
    }

    public void despawnWaypoint(UUID waypointId) {
        WaypointEntities entities = spawnedEntities.remove(waypointId);
        if (entities != null) {
            removeEntity(entities.baseId());
            removeEntity(entities.crystalId());
            removeEntity(entities.labelId());
            removeEntity(entities.interactionId());
        }
    }

    private void removeEntity(UUID id) {
        Entity entity = Bukkit.getEntity(id);
        if (entity != null) {
            entity.remove();
        }
    }

    public void despawnAll() {
        for (WaypointEntities entities : spawnedEntities.values()) {
            removeEntity(entities.baseId());
            removeEntity(entities.crystalId());
            removeEntity(entities.labelId());
            removeEntity(entities.interactionId());
        }
        spawnedEntities.clear();
    }

    public void updateLabel(UUID waypointId, String newName, String ownerName) {
        WaypointEntities entities = spawnedEntities.get(waypointId);
        if (entities != null) {
            Entity entity = Bukkit.getEntity(entities.labelId());
            if (entity instanceof TextDisplay textDisplay) {
                String text = "\n&b\u2726 &l" + newName + "\n&7" + ownerName + "\n";
                textDisplay.setText(ChatColor.translateAlternateColorCodes('&', text));
            }
        }
    }

    public void spawnAllWaypoints(Collection<Waypoint> waypoints) {
        for (Waypoint wp : waypoints) {
            spawnWaypoint(wp);
        }
    }

    public void startAnimationTask() {
        if (animationTask != null && !animationTask.isCancelled()) return;

        animationTask = new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                ticks += 2;
                float rotationAngle = (float) Math.toRadians((config.getCrystalRotationSpeed() * ticks) % 360);
                float bobOffset = (float) (Math.sin(Math.toRadians(ticks * 5)) * config.getCrystalBobAmplitude());

                for (WaypointEntities entities : spawnedEntities.values()) {
                    Entity entity = Bukkit.getEntity(entities.crystalId());
                    if (entity instanceof ItemDisplay crystal) {
                        Transformation t = crystal.getTransformation();
                        t.getLeftRotation().set(new Quaternionf().rotationY(rotationAngle));
                        t.getTranslation().set(0, bobOffset, 0);
                        crystal.setTransformation(t);

                        if (config.isAmbientParticles()) {
                            Location loc = crystal.getLocation().add(0, bobOffset, 0);
                            loc.getWorld().spawnParticle(config.getAmbientParticle(), loc, 2, 0.2, 0.2, 0.2, 0.01);
                        }
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

    private record WaypointEntities(UUID baseId, UUID crystalId, UUID labelId, UUID interactionId) {}
}
