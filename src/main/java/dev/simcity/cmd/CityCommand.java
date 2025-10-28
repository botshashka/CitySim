package dev.simcity.cmd;

import dev.simcity.SimCityPlugin;
import dev.simcity.city.City;
import dev.simcity.city.CityManager;
import dev.simcity.stats.StatsService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CityCommand implements CommandExecutor {

    private final SimCityPlugin plugin;
    private final CityManager cityManager;
    private final StatsService statsService;

    public CityCommand(SimCityPlugin plugin, CityManager cityManager, StatsService statsService) {
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.statsService = statsService;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (args.length == 0) return help(s);
        String sub = args[0].toLowerCase();

        switch (sub) {
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

            case "scoreboard": {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                if (args.length >= 2 && args[1].equalsIgnoreCase("mode")) {
                    if (args.length < 3) { p.sendMessage(ChatColor.YELLOW+"Usage: /city scoreboard mode compact|full"); return true; }
                    var mode = args[2].equalsIgnoreCase("full") ? dev.simcity.ui.ScoreboardService.Mode.FULL : dev.simcity.ui.ScoreboardService.Mode.COMPACT;
                    plugin.getScoreboardService().setMode(p.getUniqueId(), mode);
                    p.sendMessage(ChatColor.GRAY+"Scoreboard mode set to "+mode);
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

    private boolean help(CommandSender s) {
        s.sendMessage(ChatColor.GRAY + "/city stats [cityId]");
        s.sendMessage(ChatColor.GRAY + "/city titles on|off");
        s.sendMessage(ChatColor.GRAY + "/city scoreboard on|off");
        s.sendMessage(ChatColor.GRAY + "/city scoreboard mode compact|full");
        s.sendMessage(ChatColor.GRAY + "/city top [happy|pop]");
        return true;
    }
}
