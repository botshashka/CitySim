package dev.citysim.ui;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.stats.HappinessBreakdown;
import dev.citysim.stats.StatsService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ScoreboardService {
    private static final char[] UNIQUE_SUFFIX_CODES =
            new char[]{'0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f','k','l','m','n','o','r'};

    public enum Mode { COMPACT, FULL }

    private final Map<UUID, Mode> modes = new ConcurrentHashMap<>();
    private final StatsService statsService;
    private final Plugin plugin;
    private final CityManager cityManager;
    private int taskId = -1;

    private final Map<UUID, Boolean> enabled = new ConcurrentHashMap<>();
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    public ScoreboardService(Plugin plugin, CityManager cityManager, StatsService statsService) {
        this.statsService = statsService;
        this.plugin = plugin;
        this.cityManager = cityManager;
    }

    public void start() {
        if (taskId != -1) {
            return;
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 20L, 40L);
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        taskId = -1;

        var manager = Bukkit.getScoreboardManager();
        for (var entry : boards.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline() && manager != null) {
                player.setScoreboard(manager.getMainScoreboard());
            }
        }
        boards.clear();
    }

    public void setEnabled(Player player, boolean on) {
        enabled.put(player.getUniqueId(), on);
        var manager = Bukkit.getScoreboardManager();
        if (!on) {
            if (manager != null) {
                player.setScoreboard(manager.getMainScoreboard());
            }
            boards.remove(player.getUniqueId());
        } else {
            boards.remove(player.getUniqueId());
        }
    }

    public boolean isEnabled(Player player) {
        return enabled.getOrDefault(player.getUniqueId(), false);
    }

    private void tick() {
        var manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isEnabled(player)) {
                continue;
            }

            City city = cityManager.cityAt(player.getLocation());
            if (city == null) {
                player.setScoreboard(manager.getMainScoreboard());
                boards.remove(player.getUniqueId());
                continue;
            }

            statsService.updateCity(city);
            HappinessBreakdown breakdown = statsService.computeHappinessBreakdown(city);

            Scoreboard board = boards.computeIfAbsent(player.getUniqueId(), id -> manager.getNewScoreboard());
            Objective objective = board.getObjective("citysim");
            if (objective == null) {
                objective = board.registerNewObjective("citysim", "dummy", ChatColor.GOLD + "CitySim");
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            }
            objective.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + city.name);

            clearBoardEntries(board);

            List<String> lines = buildLines(city, breakdown, getMode(player.getUniqueId()));
            applyLines(objective, board, lines);

            player.setScoreboard(board);
        }
    }

    private void clearBoardEntries(Scoreboard board) {
        for (String entry : new ArrayList<>(board.getEntries())) {
            board.resetScores(entry);
        }
    }

    private List<String> buildLines(City city, HappinessBreakdown breakdown, Mode mode) {
        List<String> raw = new ArrayList<>();
        String mood = shortenText(breakdown.dominantMessage(), 24);
        switch (mode) {
            case FULL -> {
                raw.add(ChatColor.GREEN + "Population: " + ChatColor.WHITE + city.population);
                raw.add(ChatColor.AQUA + "Jobs: " + ChatColor.WHITE + city.employed + "/" + city.population);
                raw.add(ChatColor.GOLD + "Happiness: " + ChatColor.WHITE + city.happiness);
                raw.add(ChatColor.BLUE + "Mood: " + ChatColor.WHITE + mood);
                raw.add(ChatColor.DARK_GRAY + " ");
                raw.add(ChatColor.YELLOW + "Light: " + ChatColor.WHITE + formatPoints(breakdown.lightPoints));
                raw.add(ChatColor.AQUA + "Employment: " + ChatColor.WHITE + formatPoints(breakdown.employmentPoints));
                raw.add(ChatColor.GREEN + "Golems: " + ChatColor.WHITE + formatPoints(breakdown.golemPoints));
                raw.add(ChatColor.RED + "Crowding: " + ChatColor.WHITE + formatPoints(-breakdown.overcrowdingPenalty));
                raw.add(ChatColor.GRAY + "Worksites: " + ChatColor.WHITE + formatPoints(breakdown.jobDensityPoints));
                raw.add(ChatColor.DARK_GREEN + "Nature: " + ChatColor.WHITE + formatPoints(breakdown.naturePoints));
                raw.add(ChatColor.DARK_RED + "Pollution: " + ChatColor.WHITE + formatPoints(-breakdown.pollutionPenalty));
                raw.add(ChatColor.BLUE + "Beds: " + ChatColor.WHITE + formatPoints(breakdown.bedsPoints));
                raw.add(ChatColor.DARK_AQUA + "Water: " + ChatColor.WHITE + formatPoints(breakdown.waterPoints));
                raw.add(ChatColor.LIGHT_PURPLE + "Beauty: " + ChatColor.WHITE + formatPoints(breakdown.beautyPoints));
            }
            case COMPACT -> {
                raw.add(ChatColor.GREEN + "Population: " + ChatColor.WHITE + city.population);
                raw.add(ChatColor.AQUA + "Jobs: " + ChatColor.WHITE + city.employed + "/" + city.population);
                raw.add(ChatColor.GOLD + "Happiness: " + ChatColor.WHITE + city.happiness);
                raw.add(ChatColor.BLUE + "Mood: " + ChatColor.WHITE + mood);
            }
        }
        return decorateLines(raw);
    }

    private List<String> decorateLines(List<String> raw) {
        List<String> decorated = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            char code = UNIQUE_SUFFIX_CODES[i % UNIQUE_SUFFIX_CODES.length];
            decorated.add(raw.get(i) + ChatColor.COLOR_CHAR + code);
        }
        return decorated;
    }

    private String formatPoints(double value) {
        return (value >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", value);
    }

    private String shortenText(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private void applyLines(Objective objective, Scoreboard board, List<String> lines) {
        int score = lines.size();
        for (String line : lines) {
            objective.getScore(line).setScore(score--);
        }
    }

    public void setMode(UUID uuid, Mode mode) {
        modes.put(uuid, mode);
    }

    public Mode getMode(UUID uuid) {
        return modes.getOrDefault(uuid, Mode.COMPACT);
    }
}
