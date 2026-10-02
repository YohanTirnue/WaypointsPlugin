package com.tirnue.waypoints;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TeleportManager {
    private final TirnueWaypoints plugin;
    private final ConfigManager config;
    private final WaypointRenderer renderer;

    private final Map<UUID, ActiveTeleport> activeTeleports = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public TeleportManager(TirnueWaypoints plugin, ConfigManager config, WaypointRenderer renderer) {
        this.plugin = plugin;
        this.config = config;
        this.renderer = renderer;
    }

    public double calculateWarmupSeconds(Waypoint from, Waypoint to) {
        if (!from.isSameDimension(to)) {
            return config.getCrossDimensionSeconds();
        }
        double distance = from.distanceTo(to);
        double time = config.getBaseWarmupSeconds() + (distance / 100.0) * config.getSecondsPer100Blocks();
        return Math.min(time, config.getMaxWarmupSeconds());
    }

    public boolean startTeleport(Player player, Waypoint from, Waypoint to) {
        UUID pid = player.getUniqueId();
        
        if (!from.isTrusted(pid) && !from.isOwner(pid)) {
            player.sendMessage(config.getPrefix() + config.getMessage("no-permission"));
            return false;
        }

        if (isOnCooldown(pid)) {
            player.sendMessage(config.getPrefix() + config.getMessage("teleport-cooldown").replace("{seconds}", String.valueOf(getCooldownRemaining(pid))));
            return false;
        }

        if (config.isDurabilityEnabled()) {
            if (from != null && from.isDepleted()) {
                player.sendMessage(config.getPrefix() + ChatColor.RED + "This waypoint is depleted! Repair it in the management menu before warping.");
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 0.6f);
                return false;
            }
            if (to.isDepleted()) {
                player.sendMessage(config.getPrefix() + ChatColor.RED + "Destination waypoint '" + to.getName() + "' is depleted and cannot receive travelers!");
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 0.6f);
                return false;
            }
        }

        double cost = 0;
        boolean free = to.isGlobal() && config.isGlobalWaypointsFree();
        if (config.isCostEnabled() && !free) {
            String type = config.getCostType();
            if ("ECONOMY".equalsIgnoreCase(type)) {
                Economy econ = plugin.getEconomy();
                if (econ != null) {
                    double distance = from.isSameDimension(to) ? from.distanceTo(to) : 10000;
                    cost = config.getEconomyBaseCost() + (distance / 100.0) * config.getEconomyPer100Blocks();
                    cost = Math.min(cost, config.getEconomyMaxCost());
                    if (!econ.has(player, cost)) {
                        player.sendMessage(config.getPrefix() + config.getMessage("not-enough-money").replace("{cost}", String.format("%.2f", cost)));
                        return false;
                    }
                }
            } else if ("XP".equalsIgnoreCase(type)) {
                if (player.getLevel() < config.getXpLevelsPerWarp()) {
                    player.sendMessage(config.getPrefix() + config.getMessage("not-enough-xp").replace("{levels}", String.valueOf(config.getXpLevelsPerWarp())));
                    return false;
                }
            } else if ("ITEM".equalsIgnoreCase(type)) {
                Material mat = config.getCostItemType();
                int amount = config.getCostItemAmount();
                if (!player.getInventory().containsAtLeast(new ItemStack(mat), amount)) {
                    player.sendMessage(config.getPrefix() + config.getMessage("not-enough-items")
                            .replace("{amount}", String.valueOf(amount))
                            .replace("{item}", mat.name()));
                    return false;
                }
            }
        }

        // Rent-a-waypoint fee
        if (to.getUsageFee() > 0 && !to.isOwner(pid) && plugin.getConfig().getBoolean("rent.enabled", false)) {
            Economy econ = plugin.getEconomy();
            if (econ != null) {
                double fee = to.getUsageFee();
                if (!econ.has(player, fee)) {
                    player.sendMessage(config.getPrefix() + ChatColor.translateAlternateColorCodes('&', "&cNot enough money! Fee: &e$" + String.format("%.2f", fee)));
                    return false;
                }
            }
        }

        Location destinationLanding = calculateSafeLandingLocation(to);
        if (destinationLanding == null) {
            player.sendMessage(config.getPrefix() + ChatColor.RED + "Destination waypoint world is not available!");
            return false;
        }

        int totalTicks = Math.max(1, (int) (calculateWarmupSeconds(from, to) * 20));
        ActiveTeleport teleport = new ActiveTeleport(player, from, to, player.getLocation(), destinationLanding, totalTicks, cost);
        activeTeleports.put(pid, teleport);
        
        teleport.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancelTeleport(pid, "offline");
                    return;
                }

                if (config.isCancelOnMove()) {
                    if (player.getLocation().distanceSquared(teleport.startLocation) > config.getMoveThreshold() * config.getMoveThreshold()) {
                        cancelTeleport(pid, config.getPrefix() + config.getMessage("teleport-cancelled-move"));
                        return;
                    }
                }

                if (config.isCancelOnDamage()) {
                    if (player.getHealth() < teleport.initialHealth) {
                        cancelTeleport(pid, config.getPrefix() + config.getMessage("teleport-cancelled-damage"));
                        return;
                    }
                }
                
                // Keep tracking lowest health to avoid cancelling on healing
                teleport.initialHealth = Math.min(teleport.initialHealth, player.getHealth());

                teleport.ticksElapsed++;
                double progress = Math.min(1.0, (double) teleport.ticksElapsed / teleport.totalTicks);
                Location pLoc = player.getLocation();
                
                // 1. Channeling FX at origin - swirl particles every 2 ticks
                if (teleport.ticksElapsed % 2 == 0) {
                    double radius = 2.0 - (progress * 1.7);
                    int particles = 4 + (int)(progress * 11);
                    for (int i = 0; i < particles; i++) {
                        double angle = 2 * Math.PI * i / particles + (teleport.ticksElapsed * 0.2);
                        double px = pLoc.getX() + Math.cos(angle) * radius;
                        double pz = pLoc.getZ() + Math.sin(angle) * radius;
                        double py = pLoc.getY() + 0.1 + progress * 2.0;
                        ParticleUtil.spawn(pLoc.getWorld(), config.getChannelingParticle(), px, py, pz, 1, 0, 0, 0, 0);
                    }
                }

                // Origin sound every 10 ticks
                if (teleport.ticksElapsed % 10 == 0) {
                    player.playSound(pLoc, config.getChannelingSound(), (float)(0.3 + progress * 0.7), (float)(0.5 + progress * 1.5));
                }

                // 2. Materialization FX at destination (so players at the destination see someone warping in!)
                if (teleport.destinationLanding != null && teleport.destinationLanding.getWorld() != null) {
                    Location dLoc = teleport.destinationLanding;
                    World dWorld = dLoc.getWorld();
                    int dcx = dLoc.getBlockX() >> 4;
                    int dcz = dLoc.getBlockZ() >> 4;

                    if (dWorld.isChunkLoaded(dcx, dcz)) {
                        // Ground summoning rune ring (spinning circle of portal energy)
                        if (teleport.ticksElapsed % 2 == 0) {
                            double circleRadius = 0.85;
                            int points = 8;
                            double spin = teleport.ticksElapsed * 0.15;
                            for (int i = 0; i < points; i++) {
                                double angle = spin + (2 * Math.PI * i / points);
                                double cx = dLoc.getX() + Math.cos(angle) * circleRadius;
                                double cz = dLoc.getZ() + Math.sin(angle) * circleRadius;
                                ParticleUtil.spawn(dWorld, Particle.PORTAL, cx, dLoc.getY() + 0.08, cz, 1, 0, 0.02, 0, 0.01);
                            }

                            // Rising double-helix forming the player's silhouette
                            double helixRadius = 0.55;
                            double hRot = teleport.ticksElapsed * 0.35;
                            double hx1 = dLoc.getX() + Math.cos(hRot) * helixRadius;
                            double hz1 = dLoc.getZ() + Math.sin(hRot) * helixRadius;
                            double hx2 = dLoc.getX() + Math.cos(hRot + Math.PI) * helixRadius;
                            double hz2 = dLoc.getZ() + Math.sin(hRot + Math.PI) * helixRadius;
                            double hy = dLoc.getY() + (teleport.ticksElapsed % 20) * (2.0 / 20.0);

                            ParticleUtil.spawn(dWorld, Particle.REVERSE_PORTAL, hx1, hy, hz1, 1, 0, 0.02, 0, 0.01);
                            ParticleUtil.spawn(dWorld, Particle.REVERSE_PORTAL, hx2, hy, hz2, 1, 0, 0.02, 0, 0.01);
                        }

                        // Core energy sparks condensing into the landing point
                        if (teleport.ticksElapsed % 4 == 0) {
                            ParticleUtil.spawn(dWorld, Particle.END_ROD, dLoc.getX(), dLoc.getY() + 1.0, dLoc.getZ(), 2, 0.25, 0.5, 0.25, 0.02);
                        }

                        // Destination sound cue building up with countdown progress
                        if (teleport.ticksElapsed % 20 == 0) {
                            try {
                                dWorld.playSound(dLoc, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, (float)(0.4 + progress * 0.6), (float)(1.0 + progress * 0.5));
                            } catch (Throwable ignored) {}
                        }
                    }
                }

                // Action bar every 4 ticks (still looks smooth, 75% less string alloc)
                if (teleport.ticksElapsed % 4 == 0 || teleport.ticksElapsed >= teleport.totalTicks) {
                    int bars = (int)(progress * 10);
                    double timeRemaining = (teleport.totalTicks - teleport.ticksElapsed) / 20.0;
                    String msg = "\u00a7bWarping: \u00a7f[\u00a7a" + "\u25a0".repeat(bars) + "\u00a77" + "\u25a1".repeat(10 - bars) + "\u00a7f] \u00a7e" + String.format("%.1fs", Math.max(0.0, timeRemaining));
                    player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(msg));
                }

                if (teleport.ticksElapsed >= teleport.totalTicks) {
                    completeTeleport(pid, teleport);
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return true;
    }

    public boolean startScrollTeleport(Player player, Waypoint to, ItemStack scrollItem) {
        UUID pid = player.getUniqueId();

        if (isCurrentlyTeleporting(pid)) {
            player.sendMessage(config.getPrefix() + ChatColor.RED + "You are already channeling a teleportation!");
            return false;
        }

        if (isOnCooldown(pid)) {
            player.sendMessage(config.getPrefix() + config.getMessage("teleport-cooldown").replace("{seconds}", String.valueOf(getCooldownRemaining(pid))));
            return false;
        }

        if (config.isDurabilityEnabled() && to.isDepleted()) {
            player.sendMessage(config.getPrefix() + ChatColor.RED + "The destination waypoint '" + to.getName() + "' is depleted and cannot receive travelers!");
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 0.6f);
            return false;
        }

        Location destinationLanding = calculateSafeLandingLocation(to);
        if (destinationLanding == null) {
            player.sendMessage(config.getPrefix() + ChatColor.RED + "Destination waypoint world is not available!");
            return false;
        }

        int totalTicks = Math.max(20, (int) (config.getScrollWarmupSeconds() * 20));
        ActiveTeleport teleport = new ActiveTeleport(player, null, to, player.getLocation(), destinationLanding, totalTicks, 0, true, scrollItem);
        activeTeleports.put(pid, teleport);

        teleport.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancelTeleport(pid, "offline");
                    return;
                }

                if (config.isCancelOnMove()) {
                    if (player.getLocation().distanceSquared(teleport.startLocation) > config.getMoveThreshold() * config.getMoveThreshold()) {
                        cancelTeleport(pid, config.getPrefix() + config.getMessage("teleport-cancelled-move"));
                        return;
                    }
                }

                if (config.isCancelOnDamage()) {
                    if (player.getHealth() < teleport.initialHealth) {
                        cancelTeleport(pid, config.getPrefix() + config.getMessage("teleport-cancelled-damage"));
                        return;
                    }
                }

                teleport.initialHealth = Math.min(teleport.initialHealth, player.getHealth());

                teleport.ticksElapsed++;
                double progress = Math.min(1.0, (double) teleport.ticksElapsed / teleport.totalTicks);
                Location pLoc = player.getLocation();

                // Channeling FX at origin
                if (teleport.ticksElapsed % 2 == 0) {
                    double radius = 1.8 - (progress * 1.5);
                    int particles = 4 + (int) (progress * 10);
                    for (int i = 0; i < particles; i++) {
                        double angle = 2 * Math.PI * i / particles + (teleport.ticksElapsed * 0.25);
                        double px = pLoc.getX() + Math.cos(angle) * radius;
                        double pz = pLoc.getZ() + Math.sin(angle) * radius;
                        double py = pLoc.getY() + 0.1 + progress * 2.0;
                        ParticleUtil.spawn(pLoc.getWorld(), Particle.PORTAL, px, py, pz, 1, 0, 0, 0, 0);
                    }
                }

                if (teleport.ticksElapsed % 10 == 0) {
                    player.playSound(pLoc, Sound.BLOCK_BEACON_AMBIENT, (float) (0.4 + progress * 0.6), (float) (0.8 + progress * 1.2));
                }

                // Materialization FX at destination
                if (teleport.destinationLanding != null && teleport.destinationLanding.getWorld() != null) {
                    Location dLoc = teleport.destinationLanding;
                    World dWorld = dLoc.getWorld();
                    int dcx = dLoc.getBlockX() >> 4;
                    int dcz = dLoc.getBlockZ() >> 4;

                    if (dWorld.isChunkLoaded(dcx, dcz)) {
                        if (teleport.ticksElapsed % 2 == 0) {
                            double circleRadius = 0.85;
                            int points = 8;
                            double spin = teleport.ticksElapsed * 0.15;
                            for (int i = 0; i < points; i++) {
                                double angle = spin + (2 * Math.PI * i / points);
                                double cx = dLoc.getX() + Math.cos(angle) * circleRadius;
                                double cz = dLoc.getZ() + Math.sin(angle) * circleRadius;
                                ParticleUtil.spawn(dWorld, Particle.PORTAL, cx, dLoc.getY() + 0.08, cz, 1, 0, 0.02, 0, 0.01);
                            }

                            double helixRadius = 0.55;
                            double hRot = teleport.ticksElapsed * 0.35;
                            double hx1 = dLoc.getX() + Math.cos(hRot) * helixRadius;
                            double hz1 = dLoc.getZ() + Math.sin(hRot) * helixRadius;
                            double hy = dLoc.getY() + (teleport.ticksElapsed % 20) * (2.0 / 20.0);

                            ParticleUtil.spawn(dWorld, Particle.REVERSE_PORTAL, hx1, hy, hz1, 1, 0, 0.02, 0, 0.01);
                        }

                        if (teleport.ticksElapsed % 4 == 0) {
                            ParticleUtil.spawn(dWorld, Particle.END_ROD, dLoc.getX(), dLoc.getY() + 1.0, dLoc.getZ(), 2, 0.25, 0.5, 0.25, 0.02);
                        }

                        if (teleport.ticksElapsed % 20 == 0) {
                            try {
                                dWorld.playSound(dLoc, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, (float) (0.4 + progress * 0.6), (float) (1.0 + progress * 0.5));
                            } catch (Throwable ignored) {}
                        }
                    }
                }

                // Action bar progress
                if (teleport.ticksElapsed % 4 == 0 || teleport.ticksElapsed >= teleport.totalTicks) {
                    int bars = (int) (progress * 10);
                    double timeRemaining = (teleport.totalTicks - teleport.ticksElapsed) / 20.0;
                    String msg = "\u00a7dScroll Warping: \u00a7f[\u00a7a" + "\u25a0".repeat(bars) + "\u00a77" + "\u25a1".repeat(10 - bars) + "\u00a7f] \u00a7e" + String.format("%.1fs", Math.max(0.0, timeRemaining));
                    player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(msg));
                }

                if (teleport.ticksElapsed >= teleport.totalTicks) {
                    completeTeleport(pid, teleport);
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return true;
    }

    private void completeTeleport(UUID pid, ActiveTeleport teleport) {
        activeTeleports.remove(pid);
        Player player = teleport.player;

        Location dest = teleport.destinationLanding;
        if (dest == null || dest.getWorld() == null) {
            dest = calculateSafeLandingLocation(teleport.to);
        }
        if (dest == null || dest.getWorld() == null) {
            dest = teleport.to.toBukkitLocation();
        }
        if (dest == null || dest.getWorld() == null) {
            player.sendMessage(config.getPrefix() + ChatColor.RED + "Destination waypoint world is not available!");
            player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(""));
            return;
        }

        // Deduct cost or consume scroll
        if (teleport.isScrollWarp) {
            boolean consumed = plugin.getScrollManager().consumeScroll(player, teleport.to.getId());
            if (!consumed) {
                player.sendMessage(config.getPrefix() + ChatColor.RED + "Teleport cancelled: You no longer possess the attuned scroll!");
                player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(""));
                return;
            }
        } else {
            if (config.isCostEnabled() && !(teleport.to.isGlobal() && config.isGlobalWaypointsFree())) {
                String type = config.getCostType();
                if ("ECONOMY".equalsIgnoreCase(type)) {
                    Economy econ = plugin.getEconomy();
                    if (econ != null) econ.withdrawPlayer(player, teleport.costAmount);
                } else if ("XP".equalsIgnoreCase(type)) {
                    player.setLevel(player.getLevel() - config.getXpLevelsPerWarp());
                } else if ("ITEM".equalsIgnoreCase(type)) {
                    player.getInventory().removeItem(new ItemStack(config.getCostItemType(), config.getCostItemAmount()));
                }
            }

            if (teleport.to.getUsageFee() > 0 && !teleport.to.isOwner(pid)) {
                Economy econ = plugin.getEconomy();
                if (econ != null && plugin.getConfig().getBoolean("rent.enabled", false)) {
                    double fee = teleport.to.getUsageFee();
                    double taxRate = plugin.getConfig().getDouble("rent.server-tax-percent", 10) / 100.0;
                    double ownerCut = fee * (1.0 - taxRate);
                    econ.withdrawPlayer(player, fee);
                    final UUID ownerUUID = teleport.to.getOwnerUUID();
                    final double finalOwnerCut = ownerCut;
                    org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        econ.depositPlayer(org.bukkit.Bukkit.getOfflinePlayer(ownerUUID), finalOwnerCut);
                    });
                }
            }
        }

        // 1. Departure sound & particles at origin
        Location originLoc = player.getLocation().clone();
        World originWorld = originLoc.getWorld();
        if (originWorld != null) {
            try {
                originWorld.playSound(originLoc, config.getDepartureSound(), 1.0f, 1.0f);
                for (int i = 0; i < 20; i++) {
                    double angle = 2 * Math.PI * i / 20;
                    double x = Math.cos(angle) * 0.8;
                    double z = Math.sin(angle) * 0.8;
                    ParticleUtil.spawn(originWorld, config.getDepartureParticle(), originLoc.getX() + x, originLoc.getY() + 1.0, originLoc.getZ() + z, 2, 0.05, 0.2, 0.05, 0.05);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Error playing departure FX: " + t.getMessage());
            }
        }

        // 2. Perform the teleport immediately upon countdown completion!
        player.teleport(dest);

        // 3. Clear action bar and send success message
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(""));
        if (teleport.isScrollWarp) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    config.getPrefix() + " &a✦ Teleported to &e" + teleport.to.getName() + " &avia Waystone Scroll!"));
        } else {
            String successMsg = config.getMessage("teleport-success");
            if (successMsg != null && !successMsg.isEmpty()) {
                player.sendMessage(config.getPrefix() + successMsg.replace("{destination}", teleport.to.getName()));
            }
        }

        // 4. Log activity
        if (teleport.from != null) {
            teleport.from.addActivity(player.getName(), player.getUniqueId(), "WARP_FROM", teleport.to.getName());
            teleport.to.addActivity(player.getName(), player.getUniqueId(), "WARP_TO", teleport.from.getName());
        } else {
            teleport.to.addActivity(player.getName(), player.getUniqueId(), "WARP_SCROLL", "Wilderness");
        }

        // 4b. Durability degradation
        if (config.isDurabilityEnabled()) {
            int loss = config.getDurabilityLossPerTeleport();
            if (teleport.from != null && !teleport.from.isGlobal()) {
                teleport.from.damageDurability(loss);
            }
            if (!teleport.to.isGlobal()) {
                teleport.to.damageDurability(loss);
            }
            plugin.getWaypointManager().saveAsync();
        }

        // 5. BOOM particles & sound everywhere after teleporting!
        World destWorld = dest.getWorld();
        if (destWorld != null) {
            try {
                destWorld.playSound(dest, Sound.ENTITY_GENERIC_EXPLODE, 0.85f, 1.5f);
                destWorld.playSound(dest, Sound.ENTITY_PLAYER_TELEPORT, 1.0f, 1.0f);
                destWorld.playSound(dest, Sound.ITEM_TOTEM_USE, 0.6f, 1.6f);
                destWorld.playSound(dest, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.0f);
            } catch (Throwable ignored) {}

            Location burstCenter = dest.clone().add(0, 1.0, 0);
            try {
                destWorld.spawnParticle(Particle.EXPLOSION, burstCenter, 2, 0.3, 0.3, 0.3, 0);
                destWorld.spawnParticle(Particle.FLASH, burstCenter, 1, 0, 0, 0, 0);
            } catch (Throwable ignored) {}

            Particle wpParticle;
            try {
                wpParticle = Particle.valueOf(teleport.to.getParticleType());
            } catch (Exception e) {
                wpParticle = Particle.PORTAL;
            }

            // Radial 3D burst of particles everywhere around the landing player
            ParticleUtil.spawn(destWorld, wpParticle, burstCenter.getX(), burstCenter.getY(), burstCenter.getZ(), 45, 0.8, 0.8, 0.8, 0.3);
            ParticleUtil.spawn(destWorld, Particle.FIREWORK, burstCenter.getX(), burstCenter.getY(), burstCenter.getZ(), 30, 0.5, 0.6, 0.5, 0.15);
            ParticleUtil.spawn(destWorld, Particle.END_ROD, burstCenter.getX(), burstCenter.getY(), burstCenter.getZ(), 25, 0.6, 0.7, 0.6, 0.2);

            // Expanding ground shockwave ring rippling outward
            final Location shockLoc = dest.clone();
            new BukkitRunnable() {
                int ringStep = 0;
                @Override
                public void run() {
                    ringStep++;
                    if (ringStep > 6) {
                        this.cancel();
                        return;
                    }
                    double radius = ringStep * 0.6; // Expands up to 3.6 blocks
                    int points = 16 + ringStep * 4;
                    for (int i = 0; i < points; i++) {
                        double a = 2 * Math.PI * i / points;
                        double rx = shockLoc.getX() + Math.cos(a) * radius;
                        double rz = shockLoc.getZ() + Math.sin(a) * radius;
                        try {
                            destWorld.spawnParticle(Particle.REVERSE_PORTAL, rx, shockLoc.getY() + 0.12, rz, 1, 0, 0.05, 0, 0.01);
                            if (ringStep <= 3) {
                                destWorld.spawnParticle(Particle.POOF, rx, shockLoc.getY() + 0.10, rz, 1, 0, 0.02, 0, 0.01);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }

        // Set cooldown
        int cdSeconds = config.getCooldownSeconds();
        if (player.hasPermission("tirnue.waypoints.vip")) {
            cdSeconds = (int)(cdSeconds * config.getVipCooldownMultiplier());
        }
        cooldowns.put(pid, System.currentTimeMillis() + (cdSeconds * 1000L));
    }

    public void cancelTeleport(UUID playerUUID, String reason) {
        ActiveTeleport teleport = activeTeleports.remove(playerUUID);
        if (teleport != null) {
            if (teleport.task != null) {
                teleport.task.cancel();
            }
            if (!reason.equals("offline") && teleport.player.isOnline()) {
                teleport.player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(""));
                teleport.player.sendMessage(reason);
                teleport.player.playSound(teleport.player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 1.0f);
            }
            // Dissipate materialization particles at destination if chunk was loaded
            if (teleport.destinationLanding != null && teleport.destinationLanding.getWorld() != null) {
                World dWorld = teleport.destinationLanding.getWorld();
                int dcx = teleport.destinationLanding.getBlockX() >> 4;
                int dcz = teleport.destinationLanding.getBlockZ() >> 4;
                if (dWorld.isChunkLoaded(dcx, dcz)) {
                    ParticleUtil.spawn(dWorld, Particle.SMOKE, teleport.destinationLanding.clone().add(0, 1.0, 0), 15, 0.3, 0.5, 0.3, 0.05);
                    try {
                        dWorld.playSound(teleport.destinationLanding, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.2f);
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    public Location calculateSafeLandingLocation(Waypoint waypoint) {
        Location base = waypoint.toBukkitLocation();
        if (base == null || base.getWorld() == null) return null;
        World world = base.getWorld();

        double baseAngle = Math.random() * 2 * Math.PI;
        // Try up to 16 candidate directions around the waypoint
        for (int i = 0; i < 16; i++) {
            double angle = baseAngle + (i * 2 * Math.PI / 16.0);
            // Distance between 3.6 and 4.3 blocks away (safely outside 3x3 footprint and orbiting crystals)
            double dist = 3.6 + Math.random() * 0.7;
            double targetX = base.getX() + Math.cos(angle) * dist;
            double targetZ = base.getZ() + Math.sin(angle) * dist;
            int blockX = (int) Math.floor(targetX);
            int blockZ = (int) Math.floor(targetZ);

            int startY = (int) Math.floor(base.getY());
            // Scan around the waypoint's elevation (+3 down to -3) to find solid standing ground
            for (int dy = 3; dy >= -3; dy--) {
                int y = startY + dy;
                org.bukkit.block.Block ground = world.getBlockAt(blockX, y - 1, blockZ);
                org.bukkit.block.Block feet = world.getBlockAt(blockX, y, blockZ);
                org.bukkit.block.Block head = world.getBlockAt(blockX, y + 1, blockZ);

                if (isGroundSafe(ground) && isBodyPassable(feet) && isBodyPassable(head)) {
                    double landingX = blockX + 0.5;
                    double landingY = y;
                    double landingZ = blockZ + 0.5;

                    // Face towards the waypoint center!
                    double diffX = waypoint.getX() - landingX;
                    double diffZ = waypoint.getZ() - landingZ;
                    float yaw = (float) Math.toDegrees(Math.atan2(-diffX, diffZ));

                    return new Location(world, landingX, landingY, landingZ, yaw, 0.0f);
                }
            }
        }

        // Fallback: outside the structure at original Y elevation facing center
        double fbX = base.getX() + Math.cos(baseAngle) * 3.8;
        double fbZ = base.getZ() + Math.sin(baseAngle) * 3.8;
        double diffX = waypoint.getX() - fbX;
        double diffZ = waypoint.getZ() - fbZ;
        float yaw = (float) Math.toDegrees(Math.atan2(-diffX, diffZ));
        return new Location(world, fbX, base.getY(), fbZ, yaw, 0.0f);
    }

    private boolean isGroundSafe(org.bukkit.block.Block block) {
        Material mat = block.getType();
        if (!mat.isSolid()) return false;
        return mat != Material.LAVA &&
               mat != Material.FIRE &&
               mat != Material.SOUL_FIRE &&
               mat != Material.CAMPFIRE &&
               mat != Material.SOUL_CAMPFIRE &&
               mat != Material.MAGMA_BLOCK &&
               mat != Material.CACTUS &&
               mat != Material.SWEET_BERRY_BUSH &&
               mat != Material.WITHER_ROSE;
    }

    private boolean isBodyPassable(org.bukkit.block.Block block) {
        return block.isPassable() && block.getType() != Material.LAVA && block.getType() != Material.FIRE;
    }

    public boolean isCurrentlyTeleporting(UUID playerUUID) {
        return activeTeleports.containsKey(playerUUID);
    }

    public boolean isOnCooldown(UUID playerUUID) {
        if (!cooldowns.containsKey(playerUUID)) return false;
        return cooldowns.get(playerUUID) > System.currentTimeMillis();
    }

    public long getCooldownRemaining(UUID playerUUID) {
        if (!isOnCooldown(playerUUID)) return 0;
        return (cooldowns.get(playerUUID) - System.currentTimeMillis()) / 1000L;
    }

    public void clearCooldown(UUID playerUUID) {
        cooldowns.remove(playerUUID);
    }

    private static class ActiveTeleport {
        Player player;
        Waypoint from;
        Waypoint to;
        Location startLocation;
        Location destinationLanding;
        double initialHealth;
        int totalTicks;
        int ticksElapsed;
        double costAmount;
        BukkitTask task;
        boolean isScrollWarp;
        ItemStack scrollItem;

        ActiveTeleport(Player player, Waypoint from, Waypoint to, Location startLocation, Location destinationLanding, int totalTicks, double costAmount) {
            this(player, from, to, startLocation, destinationLanding, totalTicks, costAmount, false, null);
        }

        ActiveTeleport(Player player, Waypoint from, Waypoint to, Location startLocation, Location destinationLanding, int totalTicks, double costAmount, boolean isScrollWarp, ItemStack scrollItem) {
            this.player = player;
            this.from = from;
            this.to = to;
            this.startLocation = startLocation;
            this.destinationLanding = destinationLanding;
            this.initialHealth = player.getHealth();
            this.totalTicks = totalTicks;
            this.costAmount = costAmount;
            this.ticksElapsed = 0;
            this.isScrollWarp = isScrollWarp;
            this.scrollItem = scrollItem;
        }
    }
}
