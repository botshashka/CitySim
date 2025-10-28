package dev.citysim.cmd;

import dev.citysim.CitySimPlugin;
import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import dev.citysim.selection.SelectionListener;
import dev.citysim.selection.SelectionState;
import dev.citysim.stats.StatsService;
import dev.citysim.ui.ScoreboardService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.Locale;

public class CityCommand implements CommandExecutor {

    private final CitySimPlugin plugin;
    private final CityManager cityManager;
    private final StatsService statsService;

    public CityCommand(CitySimPlugin plugin, CityManager cityManager, StatsService statsService) {
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.statsService = statsService;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (args.length == 0) return help(s);
        String sub = args[0].toLowerCase();

        switch (sub) {
            case "create":
            case "add": {
                if (!checkAdmin(s)) return true;
                if (args.length < 2) {
                    s.sendMessage(ChatColor.YELLOW + "Usage: /city create <name>");
                    return true;
                }

                String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                if (name.isEmpty()) {
                    s.sendMessage(ChatColor.RED + "City name cannot be empty.");
                    return true;
                }

                try {
                    City created = cityManager.create(name);
                    cityManager.save();
                    statsService.updateCity(created, true);
                    s.sendMessage(ChatColor.GREEN + "Created new city " + created.name + " (ID: " + created.id + "). Use /city wand and /city edit " + created.id + " addcuboid to define its area.");
                } catch (IllegalArgumentException ex) {
                    s.sendMessage(ChatColor.RED + ex.getMessage());
                }
                return true;
            }

            case "list": {
                if (cityManager.all().isEmpty()) {
                    s.sendMessage(ChatColor.YELLOW + "No cities have been created yet.");
                    return true;
                }

                s.sendMessage(ChatColor.GRAY + "Cities:");
                for (City city : cityManager.all()) {
                    s.sendMessage(ChatColor.GOLD + city.id + ChatColor.GRAY + " — " + ChatColor.WHITE + city.name);
                }
                return true;
            }

            case "remove":
            case "delete": {
                if (!checkAdmin(s)) return true;
                if (args.length < 2) {
                    s.sendMessage(ChatColor.YELLOW + "Usage: /city remove <cityId>");
                    return true;
                }

                String id = args[1];
                City removed = cityManager.remove(id);
                if (removed == null) {
                    s.sendMessage(ChatColor.RED + "City with id '" + id + "' does not exist.");
                    return true;
                }

                cityManager.save();
                s.sendMessage(ChatColor.GREEN + "City '" + removed.name + "' (ID: " + removed.id + ") removed.");
                return true;
            }

            case "edit": {
                if (!checkAdmin(s)) return true;
                if (args.length < 3) {
                    sendEditUsage(s);
                    return true;
                }

                String id = args[1];
                String action = args[2].toLowerCase(Locale.ROOT);

                switch (action) {
                    case "name": {
                        if (args.length < 4) {
                            sendEditUsage(s);
                            return true;
                        }

                        String newName = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
                        if (newName.isEmpty()) {
                            s.sendMessage(ChatColor.RED + "New name cannot be empty.");
                            return true;
                        }

                        try {
                            City renamed = cityManager.rename(id, newName);
                            cityManager.save();
                            statsService.updateCity(renamed, true);
                            s.sendMessage(ChatColor.GREEN + "City renamed to " + renamed.name + " (ID: " + renamed.id + ").");
                        } catch (IllegalArgumentException ex) {
                            s.sendMessage(ChatColor.RED + ex.getMessage());
                        }
                        return true;
                    }

                    case "addcuboid": {
                        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }

                        SelectionState sel = SelectionListener.get(p);
                        if (!sel.ready()) {
                            p.sendMessage(ChatColor.RED + "You must select two corners with the CitySim wand first!");
                            return true;
                        }
                        if (sel.world != sel.pos1.getWorld() || sel.world != sel.pos2.getWorld()) {
                            p.sendMessage(ChatColor.RED + "Your selection must be in a single world.");
                            return true;
                        }
                        if (sel.world != p.getWorld()) {
                            p.sendMessage(ChatColor.RED + "You are in a different world than your selection.");
                            return true;
                        }

                        City city = cityManager.get(id);
                        if (city == null) {
                            p.sendMessage(ChatColor.RED + "City with id '" + id + "' does not exist.");
                            return true;
                        }

                        boolean fullHeight = sel.yMode == SelectionState.YMode.FULL;
                        Cuboid cuboid = new Cuboid(sel.world, sel.pos1, sel.pos2, fullHeight);

                        try {
                            int index = cityManager.addCuboid(city.id, cuboid);
                            cityManager.save();
                            statsService.updateCity(city, true);

                            int width = cuboid.maxX - cuboid.minX + 1;
                            int length = cuboid.maxZ - cuboid.minZ + 1;
                            int height = cuboid.maxY - cuboid.minY + 1;
                            String mode = fullHeight ? "full" : "span";
                            p.sendMessage(ChatColor.GREEN + "Added cuboid #" + index + " to " + city.name + " (" + width + "×" + length + "×" + height + ", mode: " + mode + ").");
                        } catch (IllegalArgumentException ex) {
                            p.sendMessage(ChatColor.RED + ex.getMessage());
                        }
                        return true;
                    }

                    case "highrise": {
                        if (args.length < 4) {
                            sendEditUsage(s);
                            return true;
                        }

                        String value = args[3];
                        boolean enable;
                        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on")) {
                            enable = true;
                        } else if (value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off")) {
                            enable = false;
                        } else {
                            s.sendMessage(ChatColor.RED + "Highrise value must be true/false.");
                            return true;
                        }

                        City city = cityManager.get(id);
                        if (city == null) {
                            s.sendMessage(ChatColor.RED + "City with id '" + id + "' does not exist.");
                            return true;
                        }

                        try {
                            cityManager.setHighrise(city.id, enable);
                            cityManager.save();
                            statsService.updateCity(city, true);
                            s.sendMessage(ChatColor.GREEN + "City '" + city.name + "' highrise set to " + enable + ".");
                        } catch (IllegalArgumentException ex) {
                            s.sendMessage(ChatColor.RED + ex.getMessage());
                        }
                        return true;
                    }

                    case "removecuboid": {
                        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }

                        City city = cityManager.get(id);
                        if (city == null) {
                            p.sendMessage(ChatColor.RED + "City with id '" + id + "' does not exist.");
                            return true;
                        }

                        int removed;
                        try {
                            removed = cityManager.removeCuboidsContaining(city.id, p.getLocation());
                        } catch (IllegalArgumentException ex) {
                            p.sendMessage(ChatColor.RED + ex.getMessage());
                            return true;
                        }

                        if (removed == 0) {
                            p.sendMessage(ChatColor.YELLOW + "You are not standing inside any cuboids for " + city.name + ".");
                            return true;
                        }

                        cityManager.save();
                        statsService.updateCity(city, true);
                        p.sendMessage(ChatColor.GREEN + "Removed " + removed + " cuboid" + (removed == 1 ? "" : "s") + " from " + city.name + ".");
                        return true;
                    }

                    default:
                        s.sendMessage(ChatColor.RED + "Unknown edit action. Use name, addcuboid, removecuboid, or highrise.");
                        return true;
                }
            }

            case "wand": {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                if (!checkAdmin(s)) return true;

                ItemStack wand = new ItemStack(SelectionListener.WAND);
                ItemMeta meta = wand.getItemMeta();
                meta.displayName(Component.text("CitySim Wand", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
                meta.lore(java.util.List.of(
                        Component.text("Left click: set corner 1", NamedTextColor.YELLOW),
                        Component.text("Right click: set corner 2", NamedTextColor.YELLOW)
                ));
                wand.setItemMeta(meta);

                p.getInventory().addItem(wand);
                p.sendMessage(ChatColor.GREEN + "CitySim wand given. Left/right click blocks to set the selection.");
                return true;
            }

            case "stats": {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                City cty = null;
                if (args.length >= 2) cty = cityManager.get(args[1]);
                if (cty == null) cty = cityManager.cityAt(p.getLocation());
                if (cty == null) { p.sendMessage(ChatColor.RED + "Stand in a city or pass /city stats <cityId>"); return true; }

                var hb = statsService.updateCity(cty, true);
                var mm = MiniMessage.miniMessage();
                String msg = """
                <gray><b>%s — City stats</b></gray>
                <gold>Population:</gold> %d  <aqua>Employed:</aqua> %d  <red>Unemployed:</red> %d
                <gold>Happiness:</gold> %d  <gray>(base 50)</gray>
                <yellow>Light:</yellow> %.1f  <aqua>Employment:</aqua> %.1f  <red>Overcrowding:</red> -%.1f
                <green>Nature:</green> %.1f  <red>Pollution:</red> -%.1f  <blue>Housing:</blue> %.1f
                """.formatted(
                        cty.name, cty.population, cty.employed, cty.unemployed,
                        hb.total,
                        hb.lightPoints, hb.employmentPoints, hb.overcrowdingPenalty,
                        hb.naturePoints, hb.pollutionPenalty, hb.housingPoints
                );
                p.sendMessage(mm.deserialize(msg));
                return true;
            }

            case "display": {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                if (args.length < 2) {
                    sendDisplayUsage(p);
                    return true;
                }

                String type = args[1].toLowerCase(Locale.ROOT);
                switch (type) {
                    case "titles": {
                        if (args.length < 3) { p.sendMessage(ChatColor.YELLOW + "Usage: /city display titles on|off"); return true; }
                        boolean on = args[2].equalsIgnoreCase("on");
                        plugin.getTitleService().setEnabled(p.getUniqueId(), on);
                        p.sendMessage(on ? ChatColor.GREEN + "Enter titles enabled" : ChatColor.RED + "Enter titles disabled");
                        return true;
                    }
                    case "bossbar": {
                        if (args.length < 3) { p.sendMessage(ChatColor.YELLOW + "Usage: /city display bossbar on|off"); return true; }
                        boolean on = args[2].equalsIgnoreCase("on");
                        plugin.getBossBarService().setEnabled(p, on);
                        p.sendMessage(on ? ChatColor.GREEN + "City bossbar enabled" : ChatColor.RED + "City bossbar disabled");
                        return true;
                    }
                    case "scoreboard": {
                        if (args.length >= 3 && args[2].equalsIgnoreCase("mode")) {
                            if (args.length < 4) { p.sendMessage(ChatColor.YELLOW + "Usage: /city display scoreboard mode compact|full"); return true; }
                            ScoreboardService.Mode mode = args[3].equalsIgnoreCase("full") ? ScoreboardService.Mode.FULL : ScoreboardService.Mode.COMPACT;
                            plugin.getScoreboardService().setMode(p.getUniqueId(), mode);
                            p.sendMessage(ChatColor.GRAY + "Scoreboard mode set to " + mode.name().toLowerCase(Locale.ROOT));
                            return true;
                        }
                        if (args.length >= 3) {
                            boolean on = args[2].equalsIgnoreCase("on");
                            plugin.getScoreboardService().setEnabled(p, on);
                            p.sendMessage(on ? ChatColor.GREEN + "Scoreboard enabled" : ChatColor.RED + "Scoreboard disabled");
                            return true;
                        }
                        p.sendMessage(ChatColor.GRAY + "/city display scoreboard on|off");
                        p.sendMessage(ChatColor.GRAY + "/city display scoreboard mode compact|full");
                        return true;
                    }
                    default:
                        sendDisplayUsage(p);
                        return true;
                }
            }

            case "ymode": {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                if (!checkAdmin(s)) return true;
                if (args.length < 2) {
                    p.sendMessage(ChatColor.YELLOW + "Usage: /city ymode <full|span>");
                    return true;
                }

                String modeArg = args[1].toLowerCase(Locale.ROOT);
                SelectionState sel = SelectionListener.get(p);
                switch (modeArg) {
                    case "full":
                        sel.yMode = SelectionState.YMode.FULL;
                        p.sendMessage(ChatColor.GRAY + "Y-mode set to full.");
                        break;
                    case "span":
                        sel.yMode = SelectionState.YMode.SPAN;
                        p.sendMessage(ChatColor.GRAY + "Y-mode set to span.");
                        break;
                    default:
                        p.sendMessage(ChatColor.RED + "Unknown mode. Use full or span.");
                        break;
                }
                return true;
            }

            case "top": {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                String metric = (args.length >= 2) ? args[1].toLowerCase() : "happy";
                java.util.List<City> list = new java.util.ArrayList<>(cityManager.all());
                if (metric.startsWith("pop")) {
                    list.sort((a,b) -> Integer.compare(b.population, a.population));
                } else {
                    list.sort((a,b) -> Integer.compare(b.happiness, a.happiness));
                }
                int limit = Math.min(10, list.size());
                StringBuilder sb = new StringBuilder();
                sb.append("Top cities by ").append(metric.startsWith("pop") ? "population" : "happiness").append(":\n");
                for (int i=0;i<limit;i++) {
                    var cty = list.get(i);
                    sb.append(String.format("%2d. %s  —  pop %d, happy %d\n", i+1, cty.name, cty.population, cty.happiness));
                }
                p.sendMessage(sb.toString());
                return true;
            }

            default:
                return help(s);
        }
    }

    private boolean checkAdmin(CommandSender sender) {
        if (sender.hasPermission("citysim.admin")) {
            return true;
        }
        sender.sendMessage(ChatColor.RED + "You do not have permission to do that.");
        return false;
    }

    private void sendEditUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage:");
        sender.sendMessage(ChatColor.YELLOW + "/city edit <cityId> name <new name>");
        sender.sendMessage(ChatColor.YELLOW + "/city edit <cityId> addcuboid");
        sender.sendMessage(ChatColor.YELLOW + "/city edit <cityId> removecuboid");
        sender.sendMessage(ChatColor.YELLOW + "/city edit <cityId> highrise <true|false>");
    }

    private void sendDisplayUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage:");
        sender.sendMessage(ChatColor.YELLOW + "/city display titles on|off");
        sender.sendMessage(ChatColor.YELLOW + "/city display bossbar on|off");
        sender.sendMessage(ChatColor.YELLOW + "/city display scoreboard on|off");
        sender.sendMessage(ChatColor.YELLOW + "/city display scoreboard mode compact|full");
    }

    private boolean help(CommandSender s) {
        s.sendMessage(ChatColor.GRAY + "/city wand");
        s.sendMessage(ChatColor.GRAY + "/city create <name>");
        s.sendMessage(ChatColor.GRAY + "/city add <name>");
        s.sendMessage(ChatColor.GRAY + "/city list");
        s.sendMessage(ChatColor.GRAY + "/city remove <cityId>");
        s.sendMessage(ChatColor.GRAY + "/city edit <cityId> name <new name>");
        s.sendMessage(ChatColor.GRAY + "/city edit <cityId> addcuboid");
        s.sendMessage(ChatColor.GRAY + "/city edit <cityId> removecuboid");
        s.sendMessage(ChatColor.GRAY + "/city edit <cityId> highrise <true|false>");
        s.sendMessage(ChatColor.GRAY + "/city ymode <full|span>");
        s.sendMessage(ChatColor.GRAY + "/city stats [cityId]");
        s.sendMessage(ChatColor.GRAY + "/city display titles on|off");
        s.sendMessage(ChatColor.GRAY + "/city display bossbar on|off");
        s.sendMessage(ChatColor.GRAY + "/city display scoreboard on|off");
        s.sendMessage(ChatColor.GRAY + "/city display scoreboard mode compact|full");
        s.sendMessage(ChatColor.GRAY + "/city top [happy|pop]");
        return true;
    }
}
