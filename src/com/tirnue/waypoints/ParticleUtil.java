package com.tirnue.waypoints;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class ParticleUtil {

    private ParticleUtil() {}

    public static void spawn(World world, Particle particle, double x, double y, double z, int count, double ox, double oy, double oz, double speed) {
        if (world == null || particle == null) return;
        try {
            Class<?> dt = particle.getDataType();
            if (dt == Void.class) {
                world.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed);
            } else if (dt == Color.class) {
                world.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, Color.fromRGB(120, 220, 255));
            } else if (dt == Particle.DustOptions.class) {
                world.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, new Particle.DustOptions(Color.AQUA, 1.0f));
            } else if (dt == Particle.DustTransition.class) {
                world.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, new Particle.DustTransition(Color.AQUA, Color.PURPLE, 1.0f));
            } else if (dt == ItemStack.class) {
                world.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, new ItemStack(Material.ENDER_PEARL));
            } else if (dt == BlockData.class) {
                world.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, Material.AMETHYST_BLOCK.createBlockData());
            } else if (dt == Float.class) {
                world.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, 1.0f);
            } else if (dt == Integer.class) {
                world.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, 1);
            } else {
                world.spawnParticle(Particle.PORTAL, x, y, z, count, ox, oy, oz, speed);
            }
        } catch (Throwable t) {
            try {
                world.spawnParticle(Particle.PORTAL, x, y, z, count, ox, oy, oz, speed);
            } catch (Throwable ignored) {}
        }
    }

    public static void spawn(World world, Particle particle, Location loc, int count, double ox, double oy, double oz, double speed) {
        if (world == null || loc == null) return;
        spawn(world, particle, loc.getX(), loc.getY(), loc.getZ(), count, ox, oy, oz, speed);
    }

    public static void spawn(Player player, Particle particle, double x, double y, double z, int count, double ox, double oy, double oz, double speed) {
        if (player == null || !player.isOnline() || particle == null) return;
        try {
            Class<?> dt = particle.getDataType();
            if (dt == Void.class) {
                player.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed);
            } else if (dt == Color.class) {
                player.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, Color.fromRGB(120, 220, 255));
            } else if (dt == Particle.DustOptions.class) {
                player.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, new Particle.DustOptions(Color.AQUA, 1.0f));
            } else if (dt == Particle.DustTransition.class) {
                player.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, new Particle.DustTransition(Color.AQUA, Color.PURPLE, 1.0f));
            } else if (dt == ItemStack.class) {
                player.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, new ItemStack(Material.ENDER_PEARL));
            } else if (dt == BlockData.class) {
                player.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, Material.AMETHYST_BLOCK.createBlockData());
            } else if (dt == Float.class) {
                player.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, 1.0f);
            } else if (dt == Integer.class) {
                player.spawnParticle(particle, x, y, z, count, ox, oy, oz, speed, 1);
            } else {
                player.spawnParticle(Particle.PORTAL, x, y, z, count, ox, oy, oz, speed);
            }
        } catch (Throwable t) {
            try {
                player.spawnParticle(Particle.PORTAL, x, y, z, count, ox, oy, oz, speed);
            } catch (Throwable ignored) {}
        }
    }
}
