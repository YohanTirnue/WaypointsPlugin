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
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
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
    private final Matrix4f reusableMatrix = new Matrix4f();

    public WaypointRenderer(TirnueWaypoints plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.wpIdKey = new NamespacedKey(plugin, "tirnue_wp_id");
    }

    public static boolean isEndCrystalType(String name) {
        return name == null || name.isEmpty() || "END_CRYSTAL".equalsIgnoreCase(name);
    }

    /**
     * Maps material/item names to valid 3D blocks so they never render as flat 2D dropped items.
     */
    public static Material toBlockMaterial(String name) {
        if (name == null || name.isEmpty() || "END_CRYSTAL".equalsIgnoreCase(name)) {
            return Material.AMETHYST_CLUSTER;
        }
        Material mat;
        try {
            mat = Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Material.AMETHYST_CLUSTER;
        }
        if (mat.isBlock()) {
            return mat;
        }
        switch (mat) {
            case AMETHYST_SHARD:
                return Material.AMETHYST_CLUSTER;
            case DIAMOND:
                return Material.DIAMOND_BLOCK;
            case EMERALD:
                return Material.EMERALD_BLOCK;
            case NETHER_STAR:
                return Material.BEACON;
            case ENDER_EYE:
            case HEART_OF_THE_SEA:
                return Material.CONDUIT;
            case PRISMARINE_CRYSTALS:
                return Material.SEA_LANTERN;
            case GLOWSTONE_DUST:
                return Material.GLOWSTONE;
            default:
                return Material.AMETHYST_CLUSTER;
        }
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
        List<BlockDisplay> structureBlocks = new ArrayList<>();
        List<Entity> crystals = new ArrayList<>(3);

        // =========================================================================
        // 1. 3x3 Wide Stone Foundation Platform (Ref: waystonereference.png)
        // =========================================================================
        // Platform floor: 2.6m x 2.6m x 0.20m
        structureBlocks.add(spawnBlockTier(world, loc, Material.POLISHED_DEEPSLATE,
                2.60f, 0.20f, 2.60f, 0.0f, 0.00f, 0.0f, wpId));

        // 4 Corner Stone Fence / Wall Buttresses (providing the 3x3 wideness!)
        float cornerDist = 1.05f;
        float[][] corners = {
                {-cornerDist, -cornerDist},
                { cornerDist, -cornerDist},
                {-cornerDist,  cornerDist},
                { cornerDist,  cornerDist}
        };

        for (float[] c : corners) {
            // Stone wall corner post
            structureBlocks.add(spawnBlockTier(world, loc, Material.DEEPSLATE_BRICK_WALL,
                    0.52f, 0.70f, 0.52f, c[0], 0.20f, c[1], wpId));
            // Corner brazier / lantern post (teal oxidized copper)
            structureBlocks.add(spawnBlockTier(world, loc, Material.OXIDIZED_COPPER,
                    0.38f, 0.25f, 0.38f, c[0], 0.90f, c[1], wpId));
        }

        // Perimeter Low Stone Wall Railings connecting the corners
        structureBlocks.add(spawnBlockTier(world, loc, Material.COBBLED_DEEPSLATE_WALL,
                1.20f, 0.35f, 0.35f, 0.0f, 0.20f, -cornerDist, wpId)); // North
        structureBlocks.add(spawnBlockTier(world, loc, Material.COBBLED_DEEPSLATE_WALL,
                1.20f, 0.35f, 0.35f, 0.0f, 0.20f,  cornerDist, wpId)); // South
        structureBlocks.add(spawnBlockTier(world, loc, Material.COBBLED_DEEPSLATE_WALL,
                0.35f, 0.35f, 1.20f, -cornerDist, 0.20f, 0.0f, wpId)); // West
        structureBlocks.add(spawnBlockTier(world, loc, Material.COBBLED_DEEPSLATE_WALL,
                0.35f, 0.35f, 1.20f,  cornerDist, 0.20f, 0.0f, wpId)); // East

        // =========================================================================
        // 2. Central 3-Block Tall Shrine Tower
        // =========================================================================
        // Tower Base Plinth (Y: 0.20 -> 0.90, height: 0.70m)
        structureBlocks.add(spawnBlockTier(world, loc, Material.CHISELED_DEEPSLATE,
                1.15f, 0.70f, 1.15f, 0.0f, 0.20f, 0.0f, wpId));

        // Inset Eye emblem on the front base
        Location eyeLoc = loc.clone().add(0, 0.55, 0.585);
        ItemDisplay insetEye = world.spawn(eyeLoc, ItemDisplay.class, entity -> {
            entity.setItemStack(new ItemStack(Material.ENDER_EYE));
            Transformation t = entity.getTransformation();
            t.getScale().set(0.28f, 0.28f, 0.28f);
            entity.setTransformation(t);
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wpId.toString());
        });

        // Glowing Green Stained Glass Core Chamber (Y: 0.90 -> 2.25, height: 1.35m)
        structureBlocks.add(spawnBlockTier(world, loc, Material.SEA_LANTERN,
                0.45f, 1.10f, 0.45f, 0.0f, 0.95f, 0.0f, wpId));
        structureBlocks.add(spawnBlockTier(world, loc, Material.LIME_STAINED_GLASS,
                0.72f, 1.35f, 0.72f, 0.0f, 0.90f, 0.0f, wpId));

        // 4 Corner Wall Columns Framing the Glass Chamber (Y: 0.90 -> 2.25)
        float colOffset = 0.38f;
        float[][] colCorners = {
                {-colOffset, -colOffset},
                { colOffset, -colOffset},
                {-colOffset,  colOffset},
                { colOffset,  colOffset}
        };
        for (float[] cc : colCorners) {
            structureBlocks.add(spawnBlockTier(world, loc, Material.DEEPSLATE_BRICK_WALL,
                    0.36f, 1.35f, 0.36f, cc[0], 0.90f, cc[1], wpId));
        }

        // Tower Capital / Overhanging Cornice (Y: 2.25 -> 2.60, height: 0.35m)
        structureBlocks.add(spawnBlockTier(world, loc, Material.DEEPSLATE_BRICKS,
                1.35f, 0.35f, 1.35f, 0.0f, 2.25f, 0.0f, wpId));

        // Top Pedestal Ring (Y: 2.60 -> 2.85, height: 0.25m)
        structureBlocks.add(spawnBlockTier(world, loc, Material.POLISHED_DEEPSLATE,
                0.95f, 0.25f, 0.95f, 0.0f, 2.60f, 0.0f, wpId));

        // Upper Pedestal Cap Step (Y: 2.85 -> 3.05, height: 0.20m, reaching ~3 blocks tall!)
        structureBlocks.add(spawnBlockTier(world, loc, Material.DEEPSLATE_TILES,
                0.65f, 0.20f, 0.65f, 0.0f, 2.85f, 0.0f, wpId));

        // =========================================================================
        // 3. Top Floating Relic & 4. Three Orbiting Items
        // =========================================================================
        String matStr = wp.getCrystalMaterial();
        boolean isEndCrystal = isEndCrystalType(matStr);
        Entity topRelic;

        if (isEndCrystal) {
            // True 3D End Crystal at top of tower!
            Location topRelicLoc = loc.clone().add(0, 3.10, 0);
            topRelic = world.spawn(topRelicLoc, EnderCrystal.class, crystal -> {
                crystal.setShowingBottom(false);
                crystal.setInvulnerable(true);
                crystal.setGravity(false);
                crystal.setPersistent(false);
                crystal.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wpId.toString());
            });

            // 3 Orbiting 3D End Crystals around the 3x3 perimeter!
            float orbitRadius = 2.10f;
            for (int i = 0; i < 3; i++) {
                float angle = (float) (i * 2.0 * Math.PI / 3.0);
                double cx = loc.getX() + Math.cos(angle) * orbitRadius;
                double cz = loc.getZ() + Math.sin(angle) * orbitRadius;
                double cy = loc.getY() + 1.85;
                Location cLoc = new Location(world, cx, cy, cz);
                EnderCrystal orbitCrystal = world.spawn(cLoc, EnderCrystal.class, crystal -> {
                    crystal.setShowingBottom(false);
                    crystal.setInvulnerable(true);
                    crystal.setGravity(false);
                    crystal.setPersistent(false);
                    crystal.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wpId.toString());
                });
                crystals.add(orbitCrystal);
            }
        } else {
            // 3D BlockDisplay with the chosen block material!
            Material crystalBlockMat = toBlockMaterial(matStr);

            Location topRelicLoc = loc.clone().add(0, 3.40, 0);
            topRelic = world.spawn(topRelicLoc, BlockDisplay.class, entity -> {
                entity.setBlock(crystalBlockMat.createBlockData());
                entity.setInterpolationDuration(2);
                entity.setInterpolationDelay(0);
                Matrix4f mat = new Matrix4f()
                        .scale(0.65f, 0.65f, 0.65f)
                        .translate(-0.5f, -0.5f, -0.5f);
                entity.setTransformationMatrix(mat);
                entity.setPersistent(false);
                entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wpId.toString());
            });

            Location orbitCenterLoc = loc.clone().add(0, 1.75, 0);
            for (int i = 0; i < 3; i++) {
                BlockDisplay orbitBlock = world.spawn(orbitCenterLoc, BlockDisplay.class, entity -> {
                    entity.setBlock(crystalBlockMat.createBlockData());
                    entity.setInterpolationDuration(2);
                    entity.setInterpolationDelay(0);
                    Matrix4f mat = new Matrix4f()
                            .scale(0.42f, 0.42f, 0.42f)
                            .translate(-0.5f, -0.5f, -0.5f);
                    entity.setTransformationMatrix(mat);
                    entity.setPersistent(false);
                    entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wpId.toString());
                });
                crystals.add(orbitBlock);
            }
        }

        // =========================================================================
        // 5. Floating Holographic Title Label (Above the Top Relic at Y: 4.35)
        // =========================================================================
        Location labelLoc = loc.clone().add(0, 4.35, 0);
        TextDisplay label = world.spawn(labelLoc, TextDisplay.class, entity -> {
            String sub = wp.isGlobal() ? "&6\u2726 &lGLOBAL WAYPOINT &6\u2726" : "&7" + wp.getOwnerName();
            String text = "\n&b\u2726 &l" + wp.getName() + "\n" + sub + "\n";
            entity.setText(ChatColor.translateAlternateColorCodes('&', text));
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setBillboard(TextDisplay.Billboard.CENTER);
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wpId.toString());
        });

        // =========================================================================
        // 6. Interaction Entity (Encompassing the Full 3x3 Base and 3.5m Height)
        // =========================================================================
        Interaction interaction = world.spawn(loc, Interaction.class, entity -> {
            entity.setInteractionWidth(2.6f);
            entity.setInteractionHeight(3.5f);
            entity.setResponsive(true);
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(wpIdKey, PersistentDataType.STRING, wpId.toString());
        });

        spawnedEntities.put(wpId, new WaypointEntities(structureBlocks, crystals, topRelic, insetEye, label, interaction, loc.clone(), isEndCrystal));
        waypointDataCache.put(wpId, wp);
    }

    private BlockDisplay spawnBlockTier(World world, Location baseLoc, Material material,
                                        float width, float height, float length,
                                        float xOffset, float yOffset, float zOffset, UUID wpId) {
        return world.spawn(baseLoc, BlockDisplay.class, entity -> {
            entity.setBlock(material.createBlockData());
            Transformation t = entity.getTransformation();
            t.getScale().set(width, height, length);
            t.getTranslation().set(xOffset - width / 2.0f, yOffset, zOffset - length / 2.0f);
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
            Waypoint wp = waypointDataCache.get(waypointId);
            String sub = (wp != null && wp.isGlobal()) ? "&6\u2726 &lGLOBAL WAYPOINT &6\u2726" : "&7" + ownerName;
            String text = "\n&b\u2726 &l" + newName + "\n" + sub + "\n";
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

                // Beacon beam particles shooting from top of structure into sky
                double bx = baseLoc.getX(), by = baseLoc.getY(), bz = baseLoc.getZ();
                for (int i = 0; i < 3; i++) {
                    player.spawnParticle(Particle.SOUL_FIRE_FLAME,
                            bx + (Math.random() - 0.5) * 0.2,
                            by + 4.0 + Math.random() * 2.5,
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
                float topAngle = (float) Math.toRadians((config.getCrystalRotationSpeed() * ticks * 0.8) % 360);
                float topBob = (float) (Math.sin(Math.toRadians(ticks * 3.0)) * 0.08);
                boolean doParticles = config.isAmbientParticles() && (ticks % 6 == 0);

                List<Map.Entry<UUID, WaypointEntities>> snapshot = new ArrayList<>(spawnedEntities.entrySet());
                for (Map.Entry<UUID, WaypointEntities> entry : snapshot) {
                    WaypointEntities entities = entry.getValue();
                    UUID wpId = entry.getKey();
                    Waypoint wp = waypointDataCache.get(wpId);

                    if (entities.isEndCrystal) {
                        // --- 1. End Crystal Mode ---
                        // Top End Crystal sparkles
                        if (ticks % 4 == 0 && entities.baseLocation != null) {
                            Location sparkLoc = entities.baseLocation.clone().add(0, 3.25, 0);
                            ParticleUtil.spawn(sparkLoc.getWorld(), Particle.HAPPY_VILLAGER, sparkLoc, 1, 0.1, 0.05, 0.1, 0.01);
                        }

                        // Orbiting 3 End Crystals around the 3x3 stone fence perimeter
                        if (entities.crystals != null && !entities.crystals.isEmpty()) {
                            float orbitRadius = 2.10f;
                            for (int i = 0; i < entities.crystals.size(); i++) {
                                Entity crystal = entities.crystals.get(i);
                                if (crystal == null || !crystal.isValid()) continue;

                                float phaseOffset = (float) (i * 2.0 * Math.PI / 3.0);
                                float angle = baseAngle + phaseOffset;
                                float bobOffset = (float) (Math.sin(bobTime + phaseOffset) * config.getCrystalBobAmplitude());

                                double cx = entities.baseLocation.getX() + Math.cos(angle) * orbitRadius;
                                double cz = entities.baseLocation.getZ() + Math.sin(angle) * orbitRadius;
                                double cy = entities.baseLocation.getY() + 1.85 + bobOffset;

                                crystal.teleport(new Location(entities.baseLocation.getWorld(), cx, cy, cz));

                                if (doParticles && wp != null) {
                                    Particle p = Particle.PORTAL;
                                    try {
                                        p = Particle.valueOf(wp.getParticleType());
                                    } catch (Exception ignored) {}
                                    ParticleUtil.spawn(entities.baseLocation.getWorld(), p, cx, cy + 0.5, cz, 1, 0.05, 0.05, 0.05, 0.01);
                                }

                                if (wp != null && wp.isDecaying()) {
                                    entities.baseLocation.getWorld().spawnParticle(Particle.SMOKE, cx, cy + 0.5, cz, 1, 0.05, 0.05, 0.05, 0.01);
                                }
                            }
                        }
                    } else {
                        // --- 2. 3D BlockDisplay Mode ---
                        if (entities.topRelic instanceof BlockDisplay && entities.topRelic.isValid()) {
                            BlockDisplay topBD = (BlockDisplay) entities.topRelic;
                            reusableQuat.rotationY(topAngle);
                            reusableMatrix.identity()
                                    .translate(0, topBob, 0)
                                    .rotate(reusableQuat)
                                    .scale(0.65f, 0.65f, 0.65f)
                                    .translate(-0.5f, -0.5f, -0.5f);
                            topBD.setTransformationMatrix(reusableMatrix);

                            if (ticks % 4 == 0 && entities.baseLocation != null) {
                                Location sparkLoc = entities.baseLocation.clone().add(0, 3.25 + topBob, 0);
                                ParticleUtil.spawn(sparkLoc.getWorld(), Particle.HAPPY_VILLAGER, sparkLoc, 1, 0.1, 0.05, 0.1, 0.01);
                            }
                        }

                        if (entities.crystals != null && !entities.crystals.isEmpty()) {
                            float orbitRadius = 1.70f;
                            for (int i = 0; i < entities.crystals.size(); i++) {
                                Entity e = entities.crystals.get(i);
                                if (!(e instanceof BlockDisplay) || !e.isValid()) continue;
                                BlockDisplay crystal = (BlockDisplay) e;

                                float phaseOffset = (float) (i * 2.0 * Math.PI / 3.0);
                                float angle = baseAngle + phaseOffset;
                                float bobOffset = (float) (Math.sin(bobTime + phaseOffset) * config.getCrystalBobAmplitude());

                                float x = (float) (Math.cos(angle) * orbitRadius);
                                float z = (float) (Math.sin(angle) * orbitRadius);

                                reusableQuat.rotationY((float) (angle * 2.5)).rotateZ(0.20f);
                                reusableMatrix.identity()
                                        .translate(x, bobOffset, z)
                                        .rotate(reusableQuat)
                                        .scale(0.42f, 0.42f, 0.42f)
                                        .translate(-0.5f, -0.5f, -0.5f);
                                crystal.setTransformationMatrix(reusableMatrix);

                                if (doParticles && wp != null && entities.baseLocation != null) {
                                    Location cLoc = entities.baseLocation.clone().add(x, 1.75 + bobOffset, z);
                                    Particle p;
                                    try {
                                        p = Particle.valueOf(wp.getParticleType());
                                    } catch (Exception ex) {
                                        p = config.getAmbientParticle();
                                    }
                                    ParticleUtil.spawn(cLoc.getWorld(), p, cLoc, 1, 0.02, 0.02, 0.02, 0.01);
                                }

                                if (wp != null && wp.isDecaying() && entities.baseLocation != null) {
                                    Location cLoc = entities.baseLocation.clone().add(x, 1.75 + bobOffset, z);
                                    cLoc.getWorld().spawnParticle(Particle.SMOKE, cLoc, 1, 0.05, 0.05, 0.05, 0.01);
                                }
                            }
                        }
                    }

                    // 3. Ambient flame particles at the 4 corner braziers
                    if (ticks % 8 == 0 && entities.baseLocation != null) {
                        float dist = 1.05f;
                        float[][] bCorners = {{-dist, -dist}, {dist, -dist}, {-dist, dist}, {dist, dist}};
                        for (float[] bc : bCorners) {
                            Location bLoc = entities.baseLocation.clone().add(bc[0], 1.18, bc[1]);
                            bLoc.getWorld().spawnParticle(Particle.FLAME, bLoc, 1, 0.02, 0.04, 0.02, 0.005);
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
        return (entity instanceof Interaction || entity instanceof EnderCrystal) &&
                entity.getPersistentDataContainer().has(wpIdKey, PersistentDataType.STRING);
    }

    private static class WaypointEntities {
        final List<BlockDisplay> structureBlocks;
        final List<Entity> crystals;
        final Entity topRelic;
        final ItemDisplay insetEye;
        final TextDisplay label;
        final Interaction interaction;
        final Location baseLocation;
        final boolean isEndCrystal;

        WaypointEntities(List<BlockDisplay> structureBlocks, List<Entity> crystals,
                         Entity topRelic, ItemDisplay insetEye, TextDisplay label,
                         Interaction interaction, Location baseLocation, boolean isEndCrystal) {
            this.structureBlocks = structureBlocks;
            this.crystals = crystals;
            this.topRelic = topRelic;
            this.insetEye = insetEye;
            this.label = label;
            this.interaction = interaction;
            this.baseLocation = baseLocation;
            this.isEndCrystal = isEndCrystal;
        }

        void removeAll() {
            if (structureBlocks != null) {
                for (BlockDisplay bd : structureBlocks) {
                    if (bd != null && bd.isValid()) bd.remove();
                }
            }
            if (crystals != null) {
                for (Entity e : crystals) {
                    if (e != null && e.isValid()) e.remove();
                }
            }
            if (topRelic != null && topRelic.isValid()) topRelic.remove();
            if (insetEye != null && insetEye.isValid()) insetEye.remove();
            if (label != null && label.isValid()) label.remove();
            if (interaction != null && interaction.isValid()) interaction.remove();
        }
    }
}
