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
            case "create": {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                if (!checkAdmin(s)) return true;
                if (args.length < 2) {
                    p.sendMessage(ChatColor.YELLOW + "Usage: /city create <name>");
                    return true;
                }

                String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                if (name.isEmpty()) {
                    p.sendMessage(ChatColor.RED + "City name cannot be empty.");
                    return true;
                }

                SelectionState sel = SelectionListener.get(p);
                if (!sel.ready()) {
                    p.sendMessage(ChatColor.RED + "Select two corners with the CitySim wand first.");
                    return true;
                }
                if (sel.world != p.getWorld()) {
                    p.sendMessage(ChatColor.RED + "Your selection is in a different world.");
                    return true;
                }

                try {
                    City created = cityManager.create(name);
                    Cuboid cuboid = new Cuboid(sel.world, sel.pos1, sel.pos2, sel.yMode == SelectionState.YMode.FULL);
                    cityManager.addCuboid(created.id, cuboid);
                    cityManager.save();
                    statsService.updateCity(created);
                    SelectionListener.selections.put(p.getUniqueId(), new SelectionState());
                    p.sendMessage(ChatColor.GREEN + "Created city " + created.name + " (" + created.id + ") with region " + formatCuboid(cuboid) + ".");
                } catch (IllegalArgumentException ex) {
                    p.sendMessage(ChatColor.RED + ex.getMessage());
                }
                return true;
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

                statsService.updateCity(cty);
                var hb = statsService.computeHappinessBreakdown(cty);
                var mm = MiniMessage.miniMessage();
                String msg = """
                <gray><b>%s — City stats</b></gray>
                <gold>Population:</gold> %d  <aqua>Employed:</aqua> %d  <red>Unemployed:</red> %d
                <gold>Happiness:</gold> %d  <gray>(base 50)</gray>
                <yellow>Light:</yellow> %.1f  <aqua>Employment:</aqua> %.1f  <green>Golems:</green> %.1f  <red>Overcrowding:</red> -%.1f  <gray>Job sites:</gray> %.1f
                <green>Nature:</green> %.1f  <red>Pollution:</red> -%.1f  <blue>Beds:</blue> %.1f  <aqua>Water:</aqua> %.1f  <light_purple>Beauty:</light_purple> %.1f
                """.formatted(
                        cty.name, cty.population, cty.employed, cty.unemployed,
                        hb.total,
                        hb.lightPoints, hb.employmentPoints, hb.golemPoints, hb.overcrowdingPenalty, hb.jobDensityPoints,
                        hb.naturePoints, hb.pollutionPenalty, hb.bedsPoints, hb.waterPoints, hb.beautyPoints
                );
                p.sendMessage(mm.deserialize(msg));
                return true;
            }

            case "titles": {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                if (args.length < 2) { p.sendMessage(ChatColor.YELLOW+"Usage: /city titles on|off"); return true; }
                boolean on = args[1].equalsIgnoreCase("on");
                plugin.getTitleService().setEnabled(p.getUniqueId(), on);
                p.sendMessage(on ? ChatColor.GREEN+"Enter titles enabled" : ChatColor.RED+"Enter titles disabled");
                return true;
            }

            case "bossbar": {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                if (args.length < 2) {
                    p.sendMessage(ChatColor.YELLOW + "Usage: /city bossbar on|off");
                    return true;
                }
                boolean on = args[1].equalsIgnoreCase("on");
                plugin.getBossBarService().setEnabled(p, on);
                p.sendMessage(on ? ChatColor.GREEN + "City bossbar enabled" : ChatColor.RED + "City bossbar disabled");
                return true;
            }

            case "scoreboard": {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                if (args.length >= 2 && args[1].equalsIgnoreCase("mode")) {
                    if (args.length < 3) { p.sendMessage(ChatColor.YELLOW+"Usage: /city scoreboard mode compact|full"); return true; }
                    ScoreboardService.Mode mode = args[2].equalsIgnoreCase("full") ? ScoreboardService.Mode.FULL : ScoreboardService.Mode.COMPACT;
                    plugin.getScoreboardService().setMode(p.getUniqueId(), mode);
                    p.sendMessage(ChatColor.GRAY+"Scoreboard mode set to "+mode.name().toLowerCase(Locale.ROOT));
                    return true;
                }
                if (args.length >= 2) {
                    boolean on = args[1].equalsIgnoreCase("on");
                    plugin.getScoreboardService().setEnabled(p, on);
                    p.sendMessage(on ? ChatColor.GREEN+"Scoreboard enabled" : ChatColor.RED+"Scoreboard disabled");
                    return true;
                }
                s.sendMessage(ChatColor.GRAY + "/city scoreboard on|off");
                s.sendMessage(ChatColor.GRAY + "/city scoreboard mode compact|full");
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

    private String formatCuboid(Cuboid c) {
        return "[" + c.world + " " +
                c.minX + "," + c.minY + "," + c.minZ + " -> " +
                c.maxX + "," + c.maxY + "," + c.maxZ + "]";
    }

    private boolean help(CommandSender s) {
        s.sendMessage(ChatColor.GRAY + "/city wand");
        s.sendMessage(ChatColor.GRAY + "/city create <name>");
        s.sendMessage(ChatColor.GRAY + "/city stats [cityId]");
        s.sendMessage(ChatColor.GRAY + "/city titles on|off");
        s.sendMessage(ChatColor.GRAY + "/city bossbar on|off");
        s.sendMessage(ChatColor.GRAY + "/city scoreboard on|off");
        s.sendMessage(ChatColor.GRAY + "/city scoreboard mode compact|full");
        s.sendMessage(ChatColor.GRAY + "/city top [happy|pop]");
        return true;
    }
}
