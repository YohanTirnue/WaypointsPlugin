package com.tirnue.waypoints;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
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
    private final Map<UUID, WaypointEntities> spawnedEntities = new HashMap<>();
    private final Map<UUID, Waypoint> waypointDataCache = new HashMap<>();
    private final NamespacedKey wpIdKey;
    private BukkitTask animationTask;
    private final Quaternionf reusableQuat = new Quaternionf();

    // Runic inscriptions matching the reference image's ancient monolith style
    private static final String RUNIC_TEXT = ChatColor.translateAlternateColorCodes('&',
            "&b&lᔑ &f&lʖ\n&f&lᓵ &b&l↸\n&b&l⎓ &f&l⊣\n&f&lꖎ &b&lᒲ\n&b&lᔑ &f&lᚢ");

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
        UUID wpId = wp.getId();
        List<BlockDisplay> structureBlocks = new ArrayList<>(8);
        List<TextDisplay> runeDisplays = new ArrayList<>(4);
        List<ItemDisplay> crystals = new ArrayList<>(3);

        // --- 1. Architectural 3-Block Tall Monolith Base (Ref: waystonereference.png) ---
        // Tier 1: Wide Plinth Base (Y: 0.00 -> 0.25)
        structureBlocks.add(spawnBlockTier(world, loc, Material.STONE_BRICKS, 1.05f, 0.25f, 1.05f, 0.00f, wpId));
        // Tier 2: Stepped Plinth Tier (Y: 0.25 -> 0.50)
        structureBlocks.add(spawnBlockTier(world, loc, Material.CHISELED_STONE_BRICKS, 0.86f, 0.25f, 0.86f, 0.25f, wpId));
        // Tier 3: Plinth Collar (Y: 0.50 -> 0.65)
        structureBlocks.add(spawnBlockTier(world, loc, Material.SMOOTH_STONE, 0.72f, 0.15f, 0.72f, 0.50f, wpId));
        // Tier 4: Monolith Column Shaft (Y: 0.65 -> 2.25, Height 1.60)
        structureBlocks.add(spawnBlockTier(world, loc, Material.SMOOTH_STONE, 0.62f, 1.60f, 0.62f, 0.65f, wpId));
        // Tier 5: Cornice Overhang / Capital (Y: 2.25 -> 2.45)
        structureBlocks.add(spawnBlockTier(world, loc, Material.STONE_BRICKS, 0.88f, 0.20f, 0.88f, 2.25f, wpId));
        // Tier 6: Stepped Roof Lower (Y: 2.45 -> 2.70)
        structureBlocks.add(spawnBlockTier(world, loc, Material.SMOOTH_STONE, 0.68f, 0.25f, 0.68f, 2.45f, wpId));
        // Tier 7: Roof Pyramid Peak (Y: 2.70 -> 2.98)
        structureBlocks.add(spawnBlockTier(world, loc, Material.POLISHED_ANDESITE, 0.46f, 0.28f, 0.46f, 2.70f, wpId));
        // Tier 8: Pinnacle Crown Tip (Y: 2.98 -> 3.06)
        structureBlocks.add(spawnBlockTier(world, loc, Material.STONE_BRICKS, 0.22f, 0.08f, 0.22f, 2.98f, wpId));

        // --- 2. Carved Glowing Runes on the 4 Faces of the Column Shaft ---
        double shaftCenterY = loc.getY() + 1.45;
        double faceOffset = 0.315; // Shaft half-width is 0.310
        // South Face (yaw = 0)
        runeDisplays.add(spawnRuneFace(world, new Location(world, loc.getX(), shaftCenterY, loc.getZ() + faceOffset, 0f, 0f), wpId));
        // North Face (yaw = 180)
        runeDisplays.add(spawnRuneFace(world, new Location(world, loc.getX(), shaftCenterY, loc.getZ() - faceOffset, 180f, 0f), wpId));
        // East Face (yaw = 270)
        runeDisplays.add(spawnRuneFace(world, new Location(world, loc.getX() + faceOffset, shaftCenterY, loc.getZ(), 270f, 0f), wpId));
        // West Face (yaw = 90)
        runeDisplays.add(spawnRuneFace(world, new Location(world, loc.getX() - faceOffset, shaftCenterY, loc.getZ(), 90f, 0f), wpId));

        // --- 3. Three Floating Items Orbiting the Waypoint ---
        Material parsedMat;
        try {
            parsedMat = Material.valueOf(wp.getCrystalMaterial());
        } catch (IllegalArgumentException e) {
            parsedMat = config.getCoreItemVisual();
        }
        final Material crystalMat = parsedMat;
        Location crystalBaseLoc = loc.clone().add(0, 1.65, 0);

        for (int i = 0; i < 3; i++) {
            ItemDisplay crystal = world.spawn(crystalBaseLoc, ItemDisplay.class, entity -> {
                entity.setItemStack(new ItemStack(crystalMat));
                entity.setInterpolationDuration(2);
                entity.setInterpolationDelay(0);
                Transformation t = entity.getTransformation();
                t.getScale().set(0.50f, 0.50f, 0.50f);
                entity.setTransformation(t);
                entity.setPersistent(false);
                entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wpId.toString());
            });
            crystals.add(crystal);
        }

        // --- 4. Floating Holographic Title Label (Above the 3-Block Monolith) ---
        Location labelLoc = loc.clone().add(0, 3.45, 0);
        TextDisplay label = world.spawn(labelLoc, TextDisplay.class, entity -> {
            String text = "\n&b\u2726 &l" + wp.getName() + "\n&7" + wp.getOwnerName() + "\n";
            entity.setText(ChatColor.translateAlternateColorCodes('&', text));
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setBillboard(TextDisplay.Billboard.CENTER);
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wpId.toString());
        });

        // --- 5. Interaction Entity (Encompassing the Full 3-Block Monolith) ---
        Interaction interaction = world.spawn(loc, Interaction.class, entity -> {
            entity.setInteractionWidth(1.4f);
            entity.setInteractionHeight(3.1f);
            entity.setResponsive(true);
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wpId.toString());
        });

        spawnedEntities.put(wpId, new WaypointEntities(structureBlocks, runeDisplays, crystals, label, interaction, loc.clone()));
        waypointDataCache.put(wpId, wp);
    }

    private BlockDisplay spawnBlockTier(World world, Location baseLoc, Material material,
                                        float width, float height, float length, float yOffset, UUID wpId) {
        return world.spawn(baseLoc, BlockDisplay.class, entity -> {
            entity.setBlock(material.createBlockData());
            Transformation t = entity.getTransformation();
            t.getScale().set(width, height, length);
            t.getTranslation().set(-width / 2.0f, yOffset, -length / 2.0f);
            entity.setTransformation(t);
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wpId.toString());
        });
    }

    private TextDisplay spawnRuneFace(World world, Location loc, UUID wpId) {
        return world.spawn(loc, TextDisplay.class, entity -> {
            entity.setText(RUNIC_TEXT);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setBillboard(TextDisplay.Billboard.FIXED);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setShadowed(true);
            Transformation t = entity.getTransformation();
            t.getScale().set(0.32f, 0.32f, 0.32f);
            entity.setTransformation(t);
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wpId.toString());
        });
    }

    public void despawnWaypoint(UUID waypointId) {
        WaypointEntities entities = spawnedEntities.remove(waypointId);
        waypointDataCache.remove(waypointId);
        if (entities != null) {
            entities.removeAll();
        }
    }

    public void despawnAll() {
        for (WaypointEntities entities : spawnedEntities.values()) {
            entities.removeAll();
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
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            for (Map.Entry<UUID, WaypointEntities> entry : spawnedEntities.entrySet()) {
                Waypoint wp = waypointDataCache.get(entry.getKey());
                if (wp == null) continue;
                if (!wp.isOwner(player.getUniqueId()) && !wp.isTrusted(player.getUniqueId())) continue;

                WaypointEntities entities = entry.getValue();
                Location baseLoc = entities.baseLocation;
                if (baseLoc == null || baseLoc.getWorld() == null || !baseLoc.getWorld().equals(player.getWorld())) continue;

                if (player.getLocation().distanceSquared(baseLoc) > 2500) continue;

                // Beacon beam particles shooting from top of 3-block structure into sky
                double bx = baseLoc.getX(), by = baseLoc.getY(), bz = baseLoc.getZ();
                for (int i = 0; i < 3; i++) {
                    player.spawnParticle(Particle.SOUL_FIRE_FLAME,
                            bx + (Math.random() - 0.5) * 0.2,
                            by + 3.2 + Math.random() * 2.5,
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

                float baseAngle = (float) Math.toRadians((config.getCrystalRotationSpeed() * ticks * 1.5) % 360);
                float bobTime = (float) Math.toRadians(ticks * 4.0);
                float orbitRadius = 0.95f;
                boolean doParticles = config.isAmbientParticles() && (ticks % 6 == 0);

                List<Map.Entry<UUID, WaypointEntities>> snapshot = new ArrayList<>(spawnedEntities.entrySet());
                for (Map.Entry<UUID, WaypointEntities> entry : snapshot) {
                    WaypointEntities entities = entry.getValue();
                    UUID wpId = entry.getKey();
                    Waypoint wp = waypointDataCache.get(wpId);

                    if (entities.crystals == null || entities.crystals.isEmpty()) continue;

                    for (int i = 0; i < entities.crystals.size(); i++) {
                        ItemDisplay crystal = entities.crystals.get(i);
                        if (crystal == null || !crystal.isValid()) continue;

                        float phaseOffset = (float) (i * 2.0 * Math.PI / 3.0);
                        float angle = baseAngle + phaseOffset;
                        float bobOffset = (float) (Math.sin(bobTime + phaseOffset) * config.getCrystalBobAmplitude());

                        float x = (float) (Math.cos(angle) * orbitRadius);
                        float z = (float) (Math.sin(angle) * orbitRadius);

                        Transformation t = crystal.getTransformation();
                        t.getScale().set(0.50f, 0.50f, 0.50f);
                        t.getTranslation().set(x, bobOffset, z);

                        // Spin each floating crystal on its own axis while it orbits
                        reusableQuat.rotationY((float) (angle * 2.5)).rotateZ(0.20f);
                        t.getLeftRotation().set(reusableQuat);
                        crystal.setTransformation(t);

                        // Ambient particles from each orbiting crystal
                        if (doParticles && wp != null) {
                            Location cLoc = entities.baseLocation.clone().add(x, 1.65 + bobOffset, z);
                            Particle p;
                            try {
                                p = Particle.valueOf(wp.getParticleType());
                            } catch (Exception e) {
                                p = config.getAmbientParticle();
                            }
                            ParticleUtil.spawn(cLoc.getWorld(), p, cLoc, 1, 0.02, 0.02, 0.02, 0.01);
                        }

                        // Decaying visual: smoke if decaying
                        if (wp != null && wp.isDecaying()) {
                            Location cLoc = entities.baseLocation.clone().add(x, 1.65 + bobOffset, z);
                            cLoc.getWorld().spawnParticle(Particle.SMOKE, cLoc, 1, 0.05, 0.05, 0.05, 0.01);
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

    private static class WaypointEntities {
        final List<BlockDisplay> structureBlocks;
        final List<TextDisplay> runeDisplays;
        final List<ItemDisplay> crystals;
        final TextDisplay label;
        final Interaction interaction;
        final Location baseLocation;

        WaypointEntities(List<BlockDisplay> structureBlocks, List<TextDisplay> runeDisplays,
                         List<ItemDisplay> crystals, TextDisplay label, Interaction interaction,
                         Location baseLocation) {
            this.structureBlocks = structureBlocks;
            this.runeDisplays = runeDisplays;
            this.crystals = crystals;
            this.label = label;
            this.interaction = interaction;
            this.baseLocation = baseLocation;
        }

        void removeAll() {
            if (structureBlocks != null) {
                for (BlockDisplay bd : structureBlocks) {
                    if (bd != null && bd.isValid()) bd.remove();
                }
            }
            if (runeDisplays != null) {
                for (TextDisplay td : runeDisplays) {
                    if (td != null && td.isValid()) td.remove();
                }
            }
            if (crystals != null) {
                for (ItemDisplay id : crystals) {
                    if (id != null && id.isValid()) id.remove();
                }
            }
            if (label != null && label.isValid()) label.remove();
            if (interaction != null && interaction.isValid()) interaction.remove();
        }
    }
}
