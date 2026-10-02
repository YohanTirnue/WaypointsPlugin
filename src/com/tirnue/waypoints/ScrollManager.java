package com.tirnue.waypoints;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ScrollManager {

    private final TirnueWaypoints plugin;
    private final WaypointManager waypointManager;
    private final ConfigManager configManager;

    private final NamespacedKey KEY_SCROLL_TYPE;
    private final NamespacedKey KEY_TARGET_WP;
    private final NamespacedKey KEY_TARGET_NAME;
    private final NamespacedKey RECIPE_KEY;

    public ScrollManager(TirnueWaypoints plugin, WaypointManager waypointManager, ConfigManager configManager) {
        this.plugin = plugin;
        this.waypointManager = waypointManager;
        this.configManager = configManager;

        this.KEY_SCROLL_TYPE = new NamespacedKey(plugin, "tirnue_wp_scroll_type");
        this.KEY_TARGET_WP = new NamespacedKey(plugin, "tirnue_wp_scroll_target");
        this.KEY_TARGET_NAME = new NamespacedKey(plugin, "tirnue_wp_scroll_target_name");
        this.RECIPE_KEY = new NamespacedKey(plugin, "tirnue_blank_scroll_recipe");
    }

    public ItemStack generateBlankScroll(int amount) {
        ItemStack item = new ItemStack(Material.PAPER, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', configManager.getBlankScrollName()));
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7An ancient parchment steeped in spatial magic."));
        lore.add("");
        lore.add(ChatColor.translateAlternateColorCodes('&', "&eRight-click any Waypoint to attune!"));
        lore.add(ChatColor.translateAlternateColorCodes('&', "&8(Allows single-use warp from anywhere)"));
        meta.setLore(lore);

        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_SCROLL_TYPE, PersistentDataType.STRING, "BLANK");
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack generateAttunedScroll(Waypoint waypoint, int amount) {
        ItemStack item = new ItemStack(Material.PAPER, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String name = configManager.getAttunedScrollNameFormat().replace("{destination}", waypoint.getName());
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7Bound Destination: &e" + waypoint.getName()));
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7World: &f" + waypoint.getWorldName()));
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7Coordinates: &f" + (int) waypoint.getX() + ", " + (int) waypoint.getY() + ", " + (int) waypoint.getZ()));
        lore.add("");
        lore.add(ChatColor.translateAlternateColorCodes('&', "&eRight-click anywhere to channel a warp!"));
        lore.add(ChatColor.translateAlternateColorCodes('&', "&cSingle-use: consumed upon arrival."));
        lore.add(ChatColor.translateAlternateColorCodes('&', "&8(Cancelled if you move or take damage)"));
        meta.setLore(lore);

        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_SCROLL_TYPE, PersistentDataType.STRING, "ATTUNED");
        pdc.set(KEY_TARGET_WP, PersistentDataType.STRING, waypoint.getId().toString());
        pdc.set(KEY_TARGET_NAME, PersistentDataType.STRING, waypoint.getName());
        item.setItemMeta(meta);
        return item;
    }

    public boolean isBlankScroll(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return "BLANK".equals(pdc.get(KEY_SCROLL_TYPE, PersistentDataType.STRING));
    }

    public boolean isAttunedScroll(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return "ATTUNED".equals(pdc.get(KEY_SCROLL_TYPE, PersistentDataType.STRING));
    }

    public UUID getTargetWaypointId(ItemStack item) {
        if (!isAttunedScroll(item)) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String str = pdc.get(KEY_TARGET_WP, PersistentDataType.STRING);
        if (str == null) return null;
        try {
            return UUID.fromString(str);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean attuneScroll(Player player, Waypoint waypoint, EquipmentSlot hand) {
        ItemStack held = (hand == EquipmentSlot.HAND) ? player.getInventory().getItemInMainHand() : player.getInventory().getItemInOffHand();
        if (!isBlankScroll(held)) return false;

        // Deduct 1 blank scroll
        held.setAmount(held.getAmount() - 1);
        if (hand == EquipmentSlot.HAND) {
            player.getInventory().setItemInMainHand(held);
        } else {
            player.getInventory().setItemInOffHand(held);
        }

        // Give 1 attuned scroll
        ItemStack attuned = generateAttunedScroll(waypoint, 1);
        if (player.getInventory().firstEmpty() == -1) {
            player.getWorld().dropItemNaturally(player.getLocation(), attuned);
        } else {
            player.getInventory().addItem(attuned);
        }

        // FX & Feedback
        player.playSound(player.getLocation(), Sound.ITEM_LODESTONE_COMPASS_LOCK, 1.0f, 1.2f);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.5f);
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.3f);
        ParticleUtil.spawn(player.getWorld(), org.bukkit.Particle.ENCHANT, player.getLocation().add(0, 1.0, 0), 30, 0.5, 0.6, 0.5, 0.2);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                configManager.getPrefix() + " &a✦ You attuned the scroll to &e" + waypoint.getName() + "&a! Right-click it anywhere out in the wild to warp."));
        return true;
    }

    public boolean consumeScroll(Player player, UUID targetWaypointId) {
        // 1. Check main hand
        ItemStack main = player.getInventory().getItemInMainHand();
        if (isAttunedScroll(main) && targetWaypointId.equals(getTargetWaypointId(main))) {
            main.setAmount(main.getAmount() - 1);
            player.getInventory().setItemInMainHand(main);
            return true;
        }

        // 2. Check offhand
        ItemStack off = player.getInventory().getItemInOffHand();
        if (isAttunedScroll(off) && targetWaypointId.equals(getTargetWaypointId(off))) {
            off.setAmount(off.getAmount() - 1);
            player.getInventory().setItemInOffHand(off);
            return true;
        }

        // 3. Search player inventory
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isAttunedScroll(item) && targetWaypointId.equals(getTargetWaypointId(item))) {
                item.setAmount(item.getAmount() - 1);
                player.getInventory().setItem(i, item);
                return true;
            }
        }

        return false;
    }

    public void registerRecipe() {
        if (!configManager.isScrollCraftingEnabled()) return;
        try {
            Bukkit.removeRecipe(RECIPE_KEY);
        } catch (Throwable ignored) {}

        ShapedRecipe recipe = new ShapedRecipe(RECIPE_KEY, generateBlankScroll(2));
        recipe.shape("PAP", "PEP", "PAP");
        recipe.setIngredient('P', Material.PAPER);
        recipe.setIngredient('A', Material.AMETHYST_SHARD);
        recipe.setIngredient('E', Material.ENDER_PEARL);

        try {
            Bukkit.addRecipe(recipe);
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not register blank scroll crafting recipe: " + t.getMessage());
        }
    }

    public void unregisterRecipe() {
        try {
            Bukkit.removeRecipe(RECIPE_KEY);
        } catch (Throwable ignored) {}
    }
}
