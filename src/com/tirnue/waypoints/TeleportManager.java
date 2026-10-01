package com.tirnue.waypoints;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
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
                double progress = (double) teleport.ticksElapsed / teleport.totalTicks;
                Location pLoc = player.getLocation();
                
                // Channeling FX - particles every 2 ticks to halve cost
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

                // Sound every 10 ticks
                if (teleport.ticksElapsed % 10 == 0) {
                    player.playSound(pLoc, config.getChannelingSound(), (float)(0.3 + progress * 0.7), (float)(0.5 + progress * 1.5));
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

    private void completeTeleport(UUID pid, ActiveTeleport teleport) {
        activeTeleports.remove(pid);
        Player player = teleport.player;

        Location dest = teleport.to.toBukkitLocation();
        if (dest == null || dest.getWorld() == null) {
            player.sendMessage(config.getPrefix() + ChatColor.RED + "Destination waypoint world is not available!");
            player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(""));
            return;
        }

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

        if (teleport.to.getUsageFee() > 0 && !teleport.to.isOwner(pid)) {
            Economy econ = plugin.getEconomy();
            if (econ != null && plugin.getConfig().getBoolean("rent.enabled", false)) {
                double fee = teleport.to.getUsageFee();
                double taxRate = plugin.getConfig().getDouble("rent.server-tax-percent", 10) / 100.0;
                double ownerCut = fee * (1.0 - taxRate);
                econ.withdrawPlayer(player, fee);
                // Deposit owner cut async to avoid Bukkit.getOfflinePlayer() blocking main thread
                final UUID ownerUUID = teleport.to.getOwnerUUID();
                final double finalOwnerCut = ownerCut;
                org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    econ.depositPlayer(org.bukkit.Bukkit.getOfflinePlayer(ownerUUID), finalOwnerCut);
                });
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
        String successMsg = config.getMessage("teleport-success");
        if (successMsg != null && !successMsg.isEmpty()) {
            player.sendMessage(config.getPrefix() + successMsg.replace("{destination}", teleport.to.getName()));
        }

        // 4. Log activity on both waypoints
        teleport.from.addActivity(player.getName(), player.getUniqueId(), "WARP_FROM", teleport.to.getName());
        teleport.to.addActivity(player.getName(), player.getUniqueId(), "WARP_TO", teleport.from.getName());

        // 5. Arrival sound & expanding particles at destination
        World destWorld = dest.getWorld();
        if (destWorld != null) {
            try {
                destWorld.playSound(dest, config.getArrivalSound(), 1.0f, 1.0f);
            } catch (Throwable ignored) {}

            new BukkitRunnable() {
                int arrivalTick = 0;
                @Override
                public void run() {
                    arrivalTick++;
                    if (arrivalTick > 10 || !player.isOnline()) {
                        this.cancel();
                        return;
                    }
                    double r = arrivalTick * 0.15;
                    for (int i = 0; i < 10; i++) {
                        double a = 2 * Math.PI * i / 10;
                        ParticleUtil.spawn(destWorld, config.getArrivalParticle(), 
                            dest.getX() + Math.cos(a) * r, dest.getY() + 0.2, dest.getZ() + Math.sin(a) * r, 
                            1, 0, 0, 0, 0);
                    }
                }
            }.runTaskTimer(plugin, 0L, 1L);
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
                teleport.player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(""));
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
