package dev.citysim.ui;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.links.CityLink;
import dev.citysim.links.LinkService;
import dev.citysim.migration.MigrationService;
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

    private final Plugin plugin;
    private final CityManager cityManager;
    private final LinkService linkService;
    private final MigrationService migrationService;
    private int taskId = -1;

    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, String> lastTitles = new HashMap<>();
    private final Map<UUID, List<String>> lastLines = new HashMap<>();
    private final DisplayPreferencesStore displayPreferencesStore;

    public ScoreboardService(Plugin plugin,
                             CityManager cityManager,
                             DisplayPreferencesStore displayPreferencesStore,
                             LinkService linkService,
                             MigrationService migrationService) {
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.displayPreferencesStore = displayPreferencesStore;
        this.linkService = linkService;
        this.migrationService = migrationService;
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

            Scoreboard board = boards.computeIfAbsent(player.getUniqueId(), id -> manager.getNewScoreboard());
            Objective objective = board.getObjective("citysim");
            if (objective == null) {
                objective = board.registerNewObjective("citysim", "dummy", ChatColor.GOLD + "CitySim");
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            }
            String title = ChatColor.YELLOW + "" + ChatColor.BOLD + city.name;
            String safeTitle = trimObjectiveTitle(title);
            List<String> lines = buildLines(city, displayPreferencesStore.getScoreboardMode(player.getUniqueId()));

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

    private List<String> buildLines(City city, Mode mode) {
        List<String> raw = mode == Mode.FULL ? buildFullLines(city) : buildCompactLines(city);
        return decorateLines(raw);
    }

    private List<String> buildCompactLines(City city) {
        List<String> lines = new ArrayList<>(3);
        lines.add(formatProsperityLine(city));
        lines.add(formatGdpLine(city));
        lines.add(formatSectorLine(city, false));
        return lines;
    }

    private List<String> buildFullLines(City city) {
        List<String> lines = new ArrayList<>(12);
        lines.add(formatProsperityLine(city));
        lines.add(formatGdpLine(city));
        lines.add(formatGdpPerCapitaLine(city));
        lines.add(formatSectorLine(city, true));
        lines.add(formatJobsPressureLine(city));
        lines.add(formatHousingPressureLine(city));
        lines.add(formatTransitPressureLine(city));
        lines.add(formatLandValueLine(city));
        lines.add(formatLinksLine(city));
        lines.add(formatTopLinkLine(city));
        lines.add(formatMigrationLine(city));
        lines.add(formatMayorsLine(city));
        return lines;
    }

    private String formatProsperityLine(City city) {
        int prosperity = city != null ? clampToInt(city.prosperity, 0, 100) : 0;
        return formatLine(ChatColor.GOLD, "Prosperity: ", String.valueOf(prosperity));
    }

    private String formatGdpLine(City city) {
        double gdp = city != null ? city.gdp : 0.0d;
        return formatLine(ChatColor.AQUA, "GDP: ", formatShortNumber(gdp));
    }

    private String formatGdpPerCapitaLine(City city) {
        double gdpPerCapita = city != null ? city.gdpPerCapita : 0.0d;
        return formatLine(ChatColor.AQUA, "GDP/cap: ", formatShortNumber(gdpPerCapita));
    }

    private String formatSectorLine(City city, boolean detailedLabel) {
        SectorLeader leader = resolveSectorLeader(city);
        String value = leader == null ? "—" : leader.name() + " " + formatPercent(leader.share());
        String label = detailedLabel ? "Sector leader: " : "Sector: ";
        return formatLine(ChatColor.GREEN, label, value);
    }

    private String formatJobsPressureLine(City city) {
        double delta = city != null ? city.jobsPressure : Double.NaN;
        return formatLine(ChatColor.RED, "Jobs pressure: ", formatSignedPercent(delta));
    }

    private String formatHousingPressureLine(City city) {
        double delta = city != null ? city.housingPressure : Double.NaN;
        return formatLine(ChatColor.BLUE, "Housing pressure: ", formatSignedPercent(delta));
    }

    private String formatTransitPressureLine(City city) {
        double delta = city != null ? city.transitPressure : Double.NaN;
        return formatLine(ChatColor.LIGHT_PURPLE, "Transit pressure: ", formatSignedPercent(delta));
    }

    private String formatLandValueLine(City city) {
        double landValue = city != null ? city.landValue : Double.NaN;
        String value = Double.isFinite(landValue)
                ? String.format(Locale.US, "%.0f", clamp(landValue, 0.0d, 100.0d))
                : "—";
        return formatLine(ChatColor.YELLOW, "Land value: ", value);
    }

    private String formatLinksLine(City city) {
        String value = "—";
        if (linkService != null && linkService.isEnabled() && city != null) {
            value = String.valueOf(Math.max(0, linkService.linkCount(city)));
        }
        return formatLine(ChatColor.DARK_AQUA, "Links: ", value);
    }

    private String formatTopLinkLine(City city) {
        String value = "—";
        if (linkService != null && linkService.isEnabled() && city != null) {
            List<CityLink> links = linkService.topLinks(city, 1);
            if (!links.isEmpty()) {
                CityLink top = links.get(0);
                String neighbor = top.neighbor() != null && top.neighbor().name != null && !top.neighbor().name.isBlank()
                        ? top.neighbor().name
                        : "?";
                int strength = clampToInt(top.strength(), 0, 100);
                value = neighbor + " S=" + strength;
            }
        }
        return formatLine(ChatColor.DARK_AQUA, "Top link: ", value);
    }

    private String formatMigrationLine(City city) {
        String value = "—";
        if (migrationService != null && city != null) {
            MigrationService.CityMigrationSnapshot snapshot = migrationService.snapshot(city);
            if (snapshot != null) {
                long net = snapshot.net();
                if (net > 0) {
                    value = "+" + net;
                } else if (net < 0) {
                    value = String.valueOf(net);
                } else {
                    value = "0";
                }
            }
        }
        return formatLine(ChatColor.GRAY, "Migration net: ", value);
    }

    private String formatMayorsLine(City city) {
        String value = "0";
        if (city != null && city.mayors != null) {
            int count = 0;
            for (String mayor : city.mayors) {
                if (mayor != null && !mayor.isBlank()) {
                    count++;
                }
            }
            value = String.valueOf(count);
        }
        return formatLine(ChatColor.GRAY, "Mayors: ", value);
    }

    private String formatLine(ChatColor labelColor, String label, String value) {
        String safeValue = (value == null || value.isBlank()) ? "—" : value;
        return labelColor + label + ChatColor.WHITE + safeValue;
    }

    private String formatShortNumber(double raw) {
        if (!Double.isFinite(raw)) {
            return "—";
        }
        double value = raw;
        double abs = Math.abs(value);
        if (abs < 1.0d) {
            return "0";
        }
        String[] suffixes = {"", "K", "M", "B", "T"};
        int index = 0;
        while (abs >= 1000.0d && index < suffixes.length - 1) {
            value /= 1000.0d;
            abs /= 1000.0d;
            index++;
        }
        if (index == 0) {
            return String.format(Locale.US, "%.0f", value);
        }
        return String.format(Locale.US, "%.1f%s", value, suffixes[index]);
    }

    private String formatPercent(double raw) {
        if (!Double.isFinite(raw)) {
            return "—";
        }
        double clamped = clamp(raw, 0.0d, 1.0d);
        return String.format(Locale.US, "%.0f%%", clamped * 100.0d);
    }

    private String formatSignedPercent(double raw) {
        if (!Double.isFinite(raw)) {
            return "—";
        }
        double percent = raw * 100.0d;
        if (Math.abs(percent) < 0.05d) {
            return "0.0%";
        }
        return String.format(Locale.US, "%+.1f%%", percent);
    }

    private SectorLeader resolveSectorLeader(City city) {
        if (city == null) {
            return null;
        }
        SectorLeader leader = null;
        leader = chooseLeader(leader, "Services", sanitizeFraction(city.sectorServ));
        leader = chooseLeader(leader, "Industry", sanitizeFraction(city.sectorInd));
        leader = chooseLeader(leader, "Agriculture", sanitizeFraction(city.sectorAgri));
        return leader;
    }

    private SectorLeader chooseLeader(SectorLeader current, String name, double share) {
        if (!Double.isFinite(share)) {
            return current;
        }
        if (current == null || share > current.share()) {
            return new SectorLeader(name, share);
        }
        return current;
    }

    private double sanitizeFraction(double value) {
        if (!Double.isFinite(value)) {
            return Double.NaN;
        }
        return clamp(value, 0.0d, 1.0d);
    }

    private double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private int clampToInt(double value, int min, int max) {
        int rounded = (int) Math.round(value);
        if (rounded < min) {
            return min;
        }
        if (rounded > max) {
            return max;
        }
        return rounded;
    }

    private record SectorLeader(String name, double share) {
    }

    private List<String> decorateLines(List<String> raw) {
        List<String> decorated = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            char code = UNIQUE_SUFFIX_CODES[i % UNIQUE_SUFFIX_CODES.length];
            decorated.add(raw.get(i) + ChatColor.COLOR_CHAR + code);
        }
        return decorated;
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
