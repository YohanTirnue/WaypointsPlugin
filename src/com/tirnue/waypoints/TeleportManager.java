package com.tirnue.waypoints;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
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
            player.sendMessage(config.getMessage("not_trusted"));
            return false;
        }

        if (isOnCooldown(pid)) {
            player.sendMessage(config.getMessage("on_cooldown").replace("%time%", String.valueOf(getCooldownRemaining(pid))));
            return false;
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
                        player.sendMessage(config.getMessage("not_enough_money"));
                        return false;
                    }
                }
            } else if ("XP".equalsIgnoreCase(type)) {
                if (player.getLevel() < config.getXpLevelsPerWarp()) {
                    player.sendMessage(config.getMessage("not_enough_xp"));
                    return false;
                }
            } else if ("ITEM".equalsIgnoreCase(type)) {
                Material mat = config.getCostItemType();
                int amount = config.getCostItemAmount();
                if (!player.getInventory().containsAtLeast(new ItemStack(mat), amount)) {
                    player.sendMessage(config.getMessage("not_enough_items"));
                    return false;
                }
            }
        }

        int totalTicks = (int) (calculateWarmupSeconds(from, to) * 20);
        ActiveTeleport teleport = new ActiveTeleport(player, from, to, player.getLocation(), totalTicks, cost);
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
                        cancelTeleport(pid, config.getMessage("teleport_cancelled_moved"));
                        return;
                    }
                }

                if (config.isCancelOnDamage()) {
                    if (player.getHealth() < teleport.initialHealth) {
                        cancelTeleport(pid, config.getMessage("teleport_cancelled_damage"));
                        return;
                    }
                }
                
                // Keep tracking lowest health to avoid cancelling on healing
                teleport.initialHealth = Math.min(teleport.initialHealth, player.getHealth());

                teleport.ticksElapsed++;
                double progress = (double) teleport.ticksElapsed / teleport.totalTicks;
                
                // Channeling FX
                double radius = 2.0 - (progress * 1.7); // 2.0 to 0.3
                int particles = 4 + (int)(progress * 11); // 4 to 15
                Location pLoc = player.getLocation();
                for (int i = 0; i < particles; i++) {
                    double angle = 2 * Math.PI * i / particles + (teleport.ticksElapsed * 0.2);
                    double x = Math.cos(angle) * radius;
                    double z = Math.sin(angle) * radius;
                    pLoc.getWorld().spawnParticle(config.getChannelingParticle(), pLoc.clone().add(x, 0.1 + progress * 2.0, z), 1, 0, 0, 0, 0);
                }

                if (teleport.ticksElapsed % 10 == 0) {
                    player.playSound(pLoc, config.getChannelingSound(), (float)(0.3 + progress * 0.7), (float)(0.5 + progress * 1.5));
                }

                // Action Bar
                int bars = (int)(progress * 10);
                StringBuilder barStr = new StringBuilder();
                for (int i = 0; i < 10; i++) {
                    barStr.append(i < bars ? "■" : "□");
                }
                double timeRemaining = (teleport.totalTicks - teleport.ticksElapsed) / 20.0;
                String msg = ChatColor.translateAlternateColorCodes('&', "&bWarping: &f[" + barStr + "&f] &e" + String.format("%.1fs", timeRemaining));
                player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(msg));

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

        // Deduct cost
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

        // Departure FX
        player.getWorld().spawnParticle(config.getDepartureParticle(), player.getLocation().add(0, 1, 0), 30, 0.5, 1, 0.5, 0.1);
        player.getWorld().playSound(player.getLocation(), config.getDepartureSound(), 1.0f, 1.0f);

        // Teleport
        Location dest = teleport.to.toBukkitLocation();
        if (dest != null) {
            player.teleport(dest);
            // Arrival FX
            player.getWorld().spawnParticle(config.getArrivalParticle(), dest.clone().add(0, 1, 0), 30, 0.5, 1, 0.5, 0.1);
            player.getWorld().playSound(dest, config.getArrivalSound(), 1.0f, 1.0f);
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
        if (teleport != null && teleport.task != null) {
            teleport.task.cancel();
            if (!reason.equals("offline") && teleport.player.isOnline()) {
                teleport.player.sendMessage(reason);
                teleport.player.playSound(teleport.player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 1.0f);
            }
        }
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
        double initialHealth;
        int totalTicks;
        int ticksElapsed;
        double costAmount;
        BukkitTask task;

        ActiveTeleport(Player player, Waypoint from, Waypoint to, Location startLocation, int totalTicks, double costAmount) {
            this.player = player;
            this.from = from;
            this.to = to;
            this.startLocation = startLocation;
            this.initialHealth = player.getHealth();
            this.totalTicks = totalTicks;
            this.costAmount = costAmount;
            this.ticksElapsed = 0;
        }
    }
}
