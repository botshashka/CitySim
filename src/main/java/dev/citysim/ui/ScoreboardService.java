package dev.citysim.ui;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.stats.HappinessBreakdown;
import dev.citysim.stats.HappinessBreakdownFormatter;
import dev.citysim.stats.HappinessBreakdownFormatter.ContributionLine;
import dev.citysim.stats.HappinessBreakdownFormatter.ContributionLists;
import dev.citysim.stats.HappinessBreakdownFormatter.ContributionType;
import dev.citysim.stats.StationCountingMode;
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

public class ScoreboardService {
    private static final char[] UNIQUE_SUFFIX_CODES =
            new char[]{'0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f','k','l','m','n','o','r'};
    private static final int OBJECTIVE_TITLE_LIMIT = 32;
    private static final String ELLIPSIS = "…";

    public enum Mode { COMPACT, FULL }

    private final StatsService statsService;
    private final Plugin plugin;
    private final CityManager cityManager;
    private int taskId = -1;

    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, String> lastTitles = new HashMap<>();
    private final Map<UUID, List<String>> lastLines = new HashMap<>();
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
        lastTitles.clear();
        lastLines.clear();
    }

    public void setEnabled(Player player, boolean on) {
        displayPreferencesStore.setScoreboardEnabled(player.getUniqueId(), on);
        var manager = Bukkit.getScoreboardManager();
        if (!on) {
            if (manager != null) {
                player.setScoreboard(manager.getMainScoreboard());
            }
            boards.remove(player.getUniqueId());
            lastTitles.remove(player.getUniqueId());
            lastLines.remove(player.getUniqueId());
        } else {
            boards.remove(player.getUniqueId());
            lastTitles.remove(player.getUniqueId());
            lastLines.remove(player.getUniqueId());
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
                UUID uuid = player.getUniqueId();
                boards.remove(uuid);
                lastTitles.remove(uuid);
                lastLines.remove(uuid);
                continue;
            }

            City city = cityManager.cityAt(player.getLocation());
            if (city == null) {
                player.setScoreboard(manager.getMainScoreboard());
                UUID uuid = player.getUniqueId();
                boards.remove(uuid);
                lastTitles.remove(uuid);
                lastLines.remove(uuid);
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
            String title = ChatColor.YELLOW + "" + ChatColor.BOLD + city.name;
            String safeTitle = trimObjectiveTitle(title);
            List<String> lines = buildLines(city, breakdown, displayPreferencesStore.getScoreboardMode(player.getUniqueId()));

            UUID uuid = player.getUniqueId();
            String cachedTitle = lastTitles.get(uuid);
            List<String> cachedLines = lastLines.get(uuid);

            boolean titleChanged = !safeTitle.equals(cachedTitle);
            boolean linesChanged = cachedLines == null || !lines.equals(cachedLines);

            if (titleChanged || linesChanged) {
                objective.setDisplayName(safeTitle);
                clearBoardEntries(board);
                applyLines(objective, board, lines);
                lastTitles.put(uuid, safeTitle);
                lastLines.put(uuid, new ArrayList<>(lines));
            }

            player.setScoreboard(board);
        }

        boards.entrySet().removeIf(entry -> {
            Player tracked = Bukkit.getPlayer(entry.getKey());
            if (tracked == null || !tracked.isOnline()) {
                lastTitles.remove(entry.getKey());
                lastLines.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    private void clearBoardEntries(Scoreboard board) {
        for (String entry : new ArrayList<>(board.getEntries())) {
            board.resetScores(entry);
        }
    }

    private String trimObjectiveTitle(String formattedTitle) {
        if (formattedTitle.length() <= OBJECTIVE_TITLE_LIMIT) {
            return formattedTitle;
        }

        int index = 0;
        StringBuilder prefix = new StringBuilder();
        while (index + 1 < formattedTitle.length() && formattedTitle.charAt(index) == ChatColor.COLOR_CHAR) {
            prefix.append(formattedTitle, index, index + 2);
            index += 2;
        }

        String baseName = formattedTitle.substring(index);
        int available = OBJECTIVE_TITLE_LIMIT - prefix.length();

        if (available <= 0) {
            return formattedTitle.substring(0, OBJECTIVE_TITLE_LIMIT);
        }

        if (baseName.length() <= available) {
            return formattedTitle.substring(0, prefix.length() + baseName.length());
        }

        int ellipsisLength = ELLIPSIS.length();
        if (ellipsisLength >= available) {
            String truncated = formattedTitle.substring(0, OBJECTIVE_TITLE_LIMIT);
            if (!truncated.isEmpty() && truncated.charAt(truncated.length() - 1) == ChatColor.COLOR_CHAR) {
                truncated = truncated.substring(0, truncated.length() - 1);
            }
            return truncated;
        }

        int textLength = available - ellipsisLength;
        if (textLength > baseName.length()) {
            textLength = baseName.length();
        }
        String candidate = prefix + baseName.substring(0, textLength) + ELLIPSIS;
        if (!candidate.isEmpty() && candidate.charAt(candidate.length() - 1) == ChatColor.COLOR_CHAR) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    private List<String> buildLines(City city, HappinessBreakdown breakdown, Mode mode) {
        StationCountingMode stationMode = statsService.getStationCountingMode();
        boolean showStations = stationMode != StationCountingMode.DISABLED;
        boolean ghostTown = breakdown != null && breakdown.isGhostTown();

        List<String> raw = new ArrayList<>();
        raw.add(ChatColor.GREEN + "Population: " + ChatColor.WHITE + city.population);
        String prosperityValue = ghostTown ? "N/A" : city.happiness + "%";
        raw.add(ChatColor.GOLD + "Prosperity: " + ChatColor.WHITE + prosperityValue);
        raw.add(ChatColor.AQUA + "Jobs: " + ChatColor.WHITE + city.employed + "/" + city.population);
        raw.add(ChatColor.BLUE + "Homes: " + ChatColor.WHITE + city.beds + "/" + city.population);
        if (showStations) {
            raw.add(ChatColor.LIGHT_PURPLE + "Stations: " + ChatColor.WHITE + city.stations);
        }

        if (mode == Mode.FULL && !ghostTown) {
            raw.add(ChatColor.DARK_GRAY + " ");
            ContributionLists contributionLists = filterTransitIfHidden(HappinessBreakdownFormatter.buildContributionLists(breakdown));

            for (ContributionLine line : contributionLists.positives()) {
                raw.add(colorFor(line.type()) + labelFor(line.type()) + ChatColor.WHITE + formatPoints(line.value()));
            }

            if (!contributionLists.negatives().isEmpty()) {
                raw.add(ChatColor.DARK_GRAY + " ");
                for (ContributionLine line : contributionLists.negatives()) {
                    raw.add(colorFor(line.type()) + labelFor(line.type()) + ChatColor.WHITE + formatPoints(line.value()));
                }
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

    private ChatColor colorFor(ContributionType type) {
        return switch (type) {
            case LIGHT -> ChatColor.YELLOW;
            case EMPLOYMENT -> ChatColor.AQUA;
            case NATURE -> ChatColor.DARK_GREEN;
            case HOUSING -> ChatColor.BLUE;
            case TRANSIT -> ChatColor.LIGHT_PURPLE;
            case OVERCROWDING -> ChatColor.RED;
            case POLLUTION -> ChatColor.DARK_RED;
        };
    }

    private String labelFor(ContributionType type) {
        return switch (type) {
            case LIGHT -> "Light: ";
            case EMPLOYMENT -> "Employment: ";
            case NATURE -> "Nature: ";
            case HOUSING -> "Housing: ";
            case TRANSIT -> "Transit: ";
            case OVERCROWDING -> "Crowding: ";
            case POLLUTION -> "Pollution: ";
        };
    }

    private ContributionLists filterTransitIfHidden(ContributionLists lists) {
        if (lists.ghostTown()) {
            return lists;
        }
        if (statsService.getStationCountingMode() != StationCountingMode.DISABLED) {
            return lists;
        }
        List<ContributionLine> positives = new ArrayList<>();
        for (ContributionLine line : lists.positives()) {
            if (line.type() != ContributionType.TRANSIT) {
                positives.add(line);
            }
        }
        List<ContributionLine> negatives = new ArrayList<>();
        for (ContributionLine line : lists.negatives()) {
            if (line.type() != ContributionType.TRANSIT) {
                negatives.add(line);
            }
        }
        return new ContributionLists(List.copyOf(positives), List.copyOf(negatives), false);
    }

    private void applyLines(Objective objective, Scoreboard board, List<String> lines) {
        int score = lines.size();
        for (String line : lines) {
            objective.getScore(line).setScore(score--);
        }
    }

    public void setMode(UUID uuid, Mode mode) {
        displayPreferencesStore.setScoreboardMode(uuid, mode);
    }

}
