
package dev.simcity.ui;

import dev.simcity.stats.StatsService;

import dev.simcity.city.City;
import dev.simcity.city.CityManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ScoreboardService {
    public enum Mode { COMPACT, FULL }
    private final java.util.Map<java.util.UUID, Mode> modes = new java.util.concurrent.ConcurrentHashMap<>();
    private final StatsService statsService;
    private final Plugin plugin;
    private final CityManager cityManager;
    private int taskId = -1;

    private final Map<UUID, Boolean> enabled = new HashMap<>();
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    public ScoreboardService(Plugin plugin, CityManager cm, StatsService statsService) { this.statsService = statsService;
        this.plugin = plugin; this.cityManager = cm;
    }

    public void start() {
        if (taskId != -1) return;
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 20L, 40L);
    }
    public void stop() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
        // clear boards
        for (var entry : boards.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null && p.isOnline()) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        boards.clear();
    }

    public void setEnabled(Player p, boolean on) {
        enabled.put(p.getUniqueId(), on);
        if (!on) {
            // reset to main scoreboard
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            boards.remove(p.getUniqueId());
        }
    }
    public boolean isEnabled(Player p) { return enabled.getOrDefault(p.getUniqueId(), false); }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isEnabled(p)) continue;
            City c = cityManager.cityAt(p.getLocation());
            if (c == null) {
                // No city; clear personal board
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                boards.remove(p.getUniqueId());
                continue;
            }
            Scoreboard board = boards.computeIfAbsent(p.getUniqueId(), id -> Bukkit.getScoreboardManager().getNewScoreboard());
            Objective obj = board.getObjective("simcity");
            if (obj == null) {
                obj = board.registerNewObjective("simcity", "dummy", ChatColor.GOLD + "SimCity");
                obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            }
            obj.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + c.name);

            // Clear old scores by resetting entries
            for (String e : board.getEntries()) board.resetScores(e);

            Score s1 = obj.getScore(ChatColor.GREEN + "Population: " + ChatColor.WHITE + c.population);
            Score s2 = obj.getScore(ChatColor.AQUA + "Employed: " + ChatColor.WHITE + c.employed);
            Score s3 = obj.getScore(ChatColor.YELLOW + "Unemployed: " + ChatColor.WHITE + c.unemployed);
            Score s4 = obj.getScore(ChatColor.GOLD + "Happiness: " + ChatColor.WHITE + c.happiness);

            // Scores must be unique integers; higher score renders on top
            s4.setScore(1);
            s3.setScore(2);
            s2.setScore(3);
            s1.setScore(4);

            p.setScoreboard(board);
        }
    }


    public void setMode(java.util.UUID uuid, Mode mode) { modes.put(uuid, mode); }
    public Mode getMode(java.util.UUID uuid) { return modes.getOrDefault(uuid, Mode.COMPACT); }
}
