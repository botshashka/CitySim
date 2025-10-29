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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class ScoreboardService {
    private static final char[] UNIQUE_SUFFIX_CODES =
            new char[]{'0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f','k','l','m','n','o','r'};

    public enum Mode { COMPACT, FULL }

    private final StatsService statsService;
    private final Plugin plugin;
    private final CityManager cityManager;
    private int taskId = -1;

    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final DisplayPreferencesStore displayPreferencesStore;

    public ScoreboardService(Plugin plugin, CityManager cityManager, StatsService statsService, DisplayPreferencesStore displayPreferencesStore) {
        this.statsService = statsService;
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.displayPreferencesStore = displayPreferencesStore;
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
        displayPreferencesStore.setScoreboardEnabled(player.getUniqueId(), on);
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
        return displayPreferencesStore.isScoreboardEnabled(player.getUniqueId());
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

            statsService.requestCityUpdate(city, false);
            HappinessBreakdown breakdown = statsService.computeHappinessBreakdown(city);

            Scoreboard board = boards.computeIfAbsent(player.getUniqueId(), id -> manager.getNewScoreboard());
            Objective objective = board.getObjective("citysim");
            if (objective == null) {
                objective = board.registerNewObjective("citysim", "dummy", ChatColor.GOLD + "CitySim");
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            }
            objective.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + city.name);

            clearBoardEntries(board);

            List<String> lines = buildLines(city, breakdown, displayPreferencesStore.getScoreboardMode(player.getUniqueId()));
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
        switch (mode) {
            case FULL -> {
                raw.add(ChatColor.GREEN + "Population: " + ChatColor.WHITE + city.population);
                raw.add(ChatColor.GOLD + "Happiness: " + ChatColor.WHITE + city.happiness + "%");
                raw.add(ChatColor.AQUA + "Jobs: " + ChatColor.WHITE + city.employed + "/" + city.population);
                raw.add(ChatColor.BLUE + "Homes: " + ChatColor.WHITE + city.beds + "/" + city.population);
                raw.add(ChatColor.LIGHT_PURPLE + "Stations: " + ChatColor.WHITE + city.stations);
                raw.add(ChatColor.DARK_GRAY + " ");

                List<ContributionLine> positiveLines = new ArrayList<>();
                List<ContributionLine> negativeLines = new ArrayList<>();

                addContributionLine(positiveLines, negativeLines, ChatColor.YELLOW, "Light: ", breakdown.lightPoints);
                addContributionLine(positiveLines, negativeLines, ChatColor.AQUA, "Employment: ", breakdown.employmentPoints);
                addContributionLine(positiveLines, negativeLines, ChatColor.DARK_GREEN, "Nature: ", breakdown.naturePoints);
                addContributionLine(positiveLines, negativeLines, ChatColor.BLUE, "Housing: ", breakdown.housingPoints);
                addContributionLine(positiveLines, negativeLines, ChatColor.LIGHT_PURPLE, "Transit: ", breakdown.transitPoints);

                if (breakdown.overcrowdingPenalty > 0) {
                    addContributionLine(positiveLines, negativeLines, ChatColor.RED, "Crowding: ", -breakdown.overcrowdingPenalty);
                }
                if (breakdown.pollutionPenalty > 0) {
                    addContributionLine(positiveLines, negativeLines, ChatColor.DARK_RED, "Pollution: ", -breakdown.pollutionPenalty);
                }

                positiveLines.sort(Comparator.comparingDouble(ContributionLine::value).reversed());
                negativeLines.sort(Comparator.comparingDouble(ContributionLine::value).reversed());

                for (ContributionLine line : positiveLines) {
                    raw.add(line.color() + line.label() + ChatColor.WHITE + formatPoints(line.value()));
                }

                if (!negativeLines.isEmpty()) {
                    raw.add(ChatColor.DARK_GRAY + " ");
                    for (ContributionLine line : negativeLines) {
                        raw.add(line.color() + line.label() + ChatColor.WHITE + formatPoints(line.value()));
                    }
                }
            }
            case COMPACT -> {
                raw.add(ChatColor.GREEN + "Population: " + ChatColor.WHITE + city.population);
                raw.add(ChatColor.GOLD + "Happiness: " + ChatColor.WHITE + city.happiness + "%");
                raw.add(ChatColor.AQUA + "Jobs: " + ChatColor.WHITE + city.employed + "/" + city.population);
                raw.add(ChatColor.BLUE + "Homes: " + ChatColor.WHITE + city.beds + "/" + city.population);
                raw.add(ChatColor.LIGHT_PURPLE + "Stations: " + ChatColor.WHITE + city.stations);
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

    private void addContributionLine(List<ContributionLine> positives, List<ContributionLine> negatives,
                                     ChatColor color, String label, double value) {
        ContributionLine line = new ContributionLine(color, label, value);
        if (value >= 0.0) {
            positives.add(line);
        } else {
            negatives.add(line);
        }
    }

    private record ContributionLine(ChatColor color, String label, double value) {}

    private void applyLines(Objective objective, Scoreboard board, List<String> lines) {
        int score = lines.size();
        for (String line : lines) {
            objective.getScore(line).setScore(score--);
        }
    }

    public void setMode(UUID uuid, Mode mode) {
        displayPreferencesStore.setScoreboardMode(uuid, mode);
    }

    public Mode getMode(UUID uuid) {
        return displayPreferencesStore.getScoreboardMode(uuid);
    }
}
