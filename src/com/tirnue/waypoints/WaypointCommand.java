package com.tirnue.waypoints;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class WaypointCommand implements CommandExecutor, TabCompleter {
    private final TirnueWaypoints plugin;

    public WaypointCommand(TirnueWaypoints plugin) {
        this.plugin = plugin;
    }

    private String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private boolean checkAdmin(CommandSender s) {
        return s.hasPermission("waypoint.admin");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command is for players only.");
            return true;
        }

        Player player = (Player) sender;
        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("create")) {
            String name = args.length > 1 ? args[1] : null;
            if (name == null) {
                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cPlease provide a name: /wp create <name>"));
                return true;
            }

            ItemStack hand = player.getInventory().getItemInMainHand();
            Material coreMat = plugin.getConfigManager().getCoreItemMaterial();
            if (hand.getType() != coreMat) {
                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cYou must be holding a Waypoint Core to create a waypoint."));
                return true;
            }

            // Check limit
            int count = plugin.getWaypointManager().getWaypointCount(player.getUniqueId());
            int limit = player.hasPermission("waypoint.vip") ? plugin.getConfigManager().getVipMaxWaypoints() : plugin.getConfigManager().getDefaultMaxWaypoints();
            if (count >= limit) {
                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cYou have reached your maximum waypoint limit (" + limit + ")."));
                return true;
            }

            hand.setAmount(hand.getAmount() - 1);
            
            Location loc = player.getLocation();
            Waypoint wp = plugin.getWaypointManager().createWaypoint(player, name, loc);
            plugin.getWaypointRenderer().spawnWaypoint(wp);
            plugin.getWaypointGUI().openManagementMenu(player, wp);
            player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aWaypoint created!"));
            return true;
        }

        if (sub.equals("rename")) {
            if (args.length < 2) {
                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cUsage: /wp rename <name>"));
                return true;
            }
            Waypoint wp = plugin.getWaypointManager().getWaypointNear(player.getLocation(), 5.0);
            if (wp != null && wp.isOwner(player.getUniqueId())) {
                wp.setName(args[1]);
                plugin.getWaypointRenderer().updateLabel(wp.getId(), args[1], wp.getOwnerName());
                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aRenamed to " + args[1]));
            } else {
                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cYou must be near a waypoint you own."));
            }
            return true;
        }

        if (sub.equals("trust") || sub.equals("untrust")) {
            if (args.length < 2) {
                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cUsage: /wp " + sub + " <player>"));
                return true;
            }
            Waypoint wp = plugin.getWaypointManager().getWaypointNear(player.getLocation(), 5.0);
            if (wp != null && wp.isOwner(player.getUniqueId())) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                if (sub.equals("trust")) {
                    plugin.getWaypointManager().trustPlayer(wp.getId(), target.getUniqueId());
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aAdded trust for " + target.getName()));
                } else {
                    plugin.getWaypointManager().untrustPlayer(wp.getId(), target.getUniqueId());
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aRemoved trust for " + target.getName()));
                }
            } else {
                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cYou must be near a waypoint you own."));
            }
            return true;
        }

        if (sub.equals("remove")) {
            Waypoint wp = plugin.getWaypointManager().getWaypointNear(player.getLocation(), 5.0);
            if (wp != null && wp.isOwner(player.getUniqueId())) {
                if (!player.isSneaking()) {
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cYou must shift-run this command to confirm destruction."));
                    return true;
                }
                plugin.getWaypointManager().deleteWaypoint(wp.getId());
                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aWaypoint destroyed."));
            } else {
                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cYou must be near a waypoint you own."));
            }
            return true;
        }

        if (sub.equals("menu")) {
            Waypoint wp = plugin.getWaypointManager().getWaypointNear(player.getLocation(), 5.0);
            if (wp != null) {
                if (wp.isOwner(player.getUniqueId())) {
                    plugin.getWaypointGUI().openManagementMenu(player, wp);
                } else if (wp.isTrusted(player.getUniqueId()) || wp.isGlobal()) {
                    plugin.getWaypointGUI().openTravelMenu(player, wp);
                } else {
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cYou do not have permission to use this waypoint."));
                }
            } else {
                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &cNo waypoint nearby."));
            }
            return true;
        }

        if (sub.equals("admin")) {
            if (!checkAdmin(player)) {
                player.sendMessage(c("&cNo permission."));
                return true;
            }
            if (args.length < 2) return true;
            String adminSub = args[1].toLowerCase();
            
            if (adminSub.equals("setglobal") || adminSub.equals("removeglobal")) {
                Waypoint wp = plugin.getWaypointManager().getWaypointNear(player.getLocation(), 5.0);
                if (wp != null) {
                    wp.setGlobal(adminSub.equals("setglobal"));
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aGlobal status set to: " + wp.isGlobal()));
                }
            } else if (adminSub.equals("remove")) {
                Waypoint wp = plugin.getWaypointManager().getWaypointNear(player.getLocation(), 5.0);
                if (wp != null) {
                    plugin.getWaypointManager().deleteWaypoint(wp.getId());
                    player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aWaypoint force-removed."));
                }
            } else if (adminSub.equals("give")) {
                if (args.length < 3) return true;
                Player target = Bukkit.getPlayer(args[2]);
                if (target != null) {
                    int amount = args.length > 3 ? Integer.parseInt(args[3]) : 1;
                    ItemStack core = new ItemStack(plugin.getConfigManager().getCoreItemMaterial(), amount);
                    ItemMeta meta = core.getItemMeta();
                    meta.setDisplayName(c(plugin.getConfigManager().getCoreItemName()));
                    List<String> lore = new ArrayList<>();
                    for (String l : plugin.getConfigManager().getCoreItemLore()) lore.add(c(l));
                    meta.setLore(lore);
                    meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "tirnue_wp_core"), PersistentDataType.BYTE, (byte) 1);
                    core.setItemMeta(meta);
                    target.getInventory().addItem(core);
                    player.sendMessage(c("&aGave " + amount + " core(s) to " + target.getName()));
                }
            } else if (adminSub.equals("reload")) {
                plugin.getConfigManager().reloadConfig();
                player.sendMessage(c(plugin.getConfigManager().getPrefix() + " &aConfig reloaded."));
            } else if (adminSub.equals("network")) {
                plugin.getWaypointGUI().openAdminNetworkMenu(player);
            }
            return true;
        }

        sendHelp(player);
        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage(c("&6--- Waypoint Help ---"));
        player.sendMessage(c("&e/wp create <name> &7- Create waypoint"));
        player.sendMessage(c("&e/wp rename <name> &7- Rename nearby waypoint"));
        player.sendMessage(c("&e/wp trust <player> &7- Trust player"));
        player.sendMessage(c("&e/wp untrust <player> &7- Untrust player"));
        player.sendMessage(c("&e/wp remove &7- Remove nearby waypoint"));
        player.sendMessage(c("&e/wp menu &7- Open menu for nearby waypoint"));
        if (checkAdmin(player)) {
            player.sendMessage(c("&c/wp admin ..."));
        }
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> comps = new ArrayList<>();
        if (args.length == 1) {
            comps.add("create"); comps.add("rename"); comps.add("trust"); comps.add("untrust"); 
            comps.add("remove"); comps.add("menu"); comps.add("help");
            if (checkAdmin(sender)) comps.add("admin");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin") && checkAdmin(sender)) {
            comps.add("setglobal"); comps.add("removeglobal"); comps.add("remove"); comps.add("give"); comps.add("reload"); comps.add("network");
        }
        return comps;
    }
}
