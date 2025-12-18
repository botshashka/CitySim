package dev.citysim.ui;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.links.LinkService;
import dev.citysim.migration.MigrationService;
import dev.citysim.stats.EconomyBreakdown;
import dev.citysim.util.TrendUtil;
import dev.citysim.util.TrendUtil.TrendDirection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Criteria;
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
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final char LEGACY_COLOR_CHAR = LegacyComponentSerializer.SECTION_CHAR;
    private static final Component DEFAULT_OBJECTIVE_NAME = Component.text("CitySim", NamedTextColor.GOLD);
    private static final TextColor VALUE_COLOR = NamedTextColor.WHITE;

    public enum Mode { COMPACT, FULL }

    private final Plugin plugin;
    private final CityManager cityManager;
    private final LinkService linkService;
    private final MigrationService migrationService;
    private final TrendUtil trendUtil = new TrendUtil();
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
                objective = board.registerNewObjective("citysim", Criteria.DUMMY, DEFAULT_OBJECTIVE_NAME);
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            }
            Component titleComponent = Component.text(city.name, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD);
            String legacyTitle = LEGACY.serialize(titleComponent);
            String safeTitle = trimObjectiveTitle(legacyTitle);
            Component safeTitleComponent = LEGACY.deserialize(safeTitle);
            List<String> lines = buildLines(city, displayPreferencesStore.getScoreboardMode(player.getUniqueId()));

            UUID uuid = player.getUniqueId();
            String cachedTitle = lastTitles.get(uuid);
            List<String> cachedLines = lastLines.get(uuid);

            boolean titleChanged = !safeTitle.equals(cachedTitle);
            boolean linesChanged = cachedLines == null || !lines.equals(cachedLines);

            if (titleChanged || linesChanged) {
                objective.displayName(safeTitleComponent);
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
        while (index + 1 < formattedTitle.length() && formattedTitle.charAt(index) == LEGACY_COLOR_CHAR) {
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
            if (!truncated.isEmpty() && truncated.charAt(truncated.length() - 1) == LEGACY_COLOR_CHAR) {
                truncated = truncated.substring(0, truncated.length() - 1);
            }
            return truncated;
        }

        int textLength = available - ellipsisLength;
        if (textLength > baseName.length()) {
            textLength = baseName.length();
        }
        String candidate = prefix + baseName.substring(0, textLength) + ELLIPSIS;
        if (!candidate.isEmpty() && candidate.charAt(candidate.length() - 1) == LEGACY_COLOR_CHAR) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    private List<String> buildLines(City city, Mode mode) {
        List<String> raw = mode == Mode.FULL ? buildFullLines(city) : buildCompactLines(city);
        return decorateLines(raw);
    }

    private List<String> buildFullLines(City city) {
        List<String> lines = new ArrayList<>(12);
        addIfPresent(lines, formatCityLine(city));
        addIfPresent(lines, formatProsperityLine(city));
        addIfPresent(lines, formatPopulationLine(city));
        addIfPresent(lines, formatGdpLine(city));
        addIfPresent(lines, formatGdpPerCapitaLine(city));
        addIfPresent(lines, formatLandLine(city));
        addIfPresent(lines, formatJobsDeltaLine(city));
        addIfPresent(lines, formatHousingDeltaLine(city));
        addIfPresent(lines, formatSectorLine(city));
        List<String> connectivityLines = formatConnectivityLines(city);
        connectivityLines.forEach(line -> addIfPresent(lines, line));
        return lines;
    }

    private List<String> buildCompactLines(City city) {
        List<String> lines = new ArrayList<>(5);
        addIfPresent(lines, formatCityLine(city));
        addIfPresent(lines, formatProsperityLine(city));
        addIfPresent(lines, formatPopulationLine(city));
        addIfPresent(lines, formatGdpPerCapitaLine(city));
        addIfPresent(lines, formatSectorLine(city));
        return lines;
    }

    private String formatCityLine(City city) {
        if (city == null || city.name == null || city.name.isBlank()) {
            return null;
        }
        return formatLine(NamedTextColor.YELLOW, "City: ", city.name);
    }

    private String formatProsperityLine(City city) {
        if (city == null) {
            return null;
        }
        double prosperity = clampToInt(city.prosperity, 0, 100);
        TrendDirection trend = arrowForMetric(city, TrendUtil.Metric.PROSPERITY, prosperity);
        return formatLineWithArrow(NamedTextColor.GOLD, "Prosperity: ", prosperity + "%", trend);
    }

    private String formatPopulationLine(City city) {
        if (city == null) {
            return null;
        }
        int population = Math.max(0, city.population);
        String formatted = String.format(Locale.US, "%,d", population);
        return formatLine(NamedTextColor.GREEN, "Pop: ", formatted);
    }

    private String formatGdpLine(City city) {
        if (city == null || Double.isNaN(city.gdp)) {
            return null;
        }
        double gdp = city.gdp;
        String value = formatShortNumber(gdp);
        TrendDirection trend = Double.isFinite(gdp) ? arrowForMetric(city, TrendUtil.Metric.GDP, gdp) : TrendDirection.FLAT;
        return formatLineWithArrow(NamedTextColor.AQUA, "GDP: ", value, trend);
    }

    private String formatGdpPerCapitaLine(City city) {
        if (city == null || Double.isNaN(city.gdpPerCapita)) {
            return null;
        }
        double gdpPerCapita = city.gdpPerCapita;
        String value = formatShortNumber(gdpPerCapita);
        TrendDirection trend = Double.isFinite(gdpPerCapita) ? arrowForMetric(city, TrendUtil.Metric.GDP_PER_CAPITA, gdpPerCapita) : TrendDirection.FLAT;
        return formatLineWithArrow(NamedTextColor.AQUA, "GDPpc: ", value, trend);
    }

    private String formatLandLine(City city) {
        if (city == null || Double.isNaN(city.landValue)) {
            return null;
        }
        double landValue = city.landValue;
        String value = Double.isFinite(landValue)
                ? String.format(Locale.US, "%.0f", clamp(landValue, 0.0d, 100.0d))
                : "—";
        TrendDirection trend = Double.isFinite(landValue) ? arrowForMetric(city, TrendUtil.Metric.LAND_VALUE, landValue) : TrendDirection.FLAT;
        return formatLineWithArrow(NamedTextColor.YELLOW, "Land: ", value, trend);
    }

    private String formatJobsDeltaLine(City city) {
        return formatDeltaLine(city, "JobsΔ: ", city != null ? city.jobsPressure : null,
                TrendUtil.Metric.JOBS_DELTA, NamedTextColor.RED);
    }

    private String formatHousingDeltaLine(City city) {
        return formatDeltaLine(city, "HousΔ: ", city != null ? city.housingPressure : null,
                TrendUtil.Metric.HOUSING_DELTA, NamedTextColor.BLUE);
    }

    private String formatDeltaLine(City city, String label, Double delta, TrendUtil.Metric metric, TextColor color) {
        String value = "—";
        TrendDirection trend = TrendDirection.FLAT;
        if (delta != null && Double.isFinite(delta)) {
            value = String.format(Locale.US, "%+.2f", delta);
            trend = arrowForMetric(city, metric, delta);
        }
        return formatLineWithArrow(color, label, value, trend);
    }

    private String formatSectorLine(City city) {
        SectorLeader leader = resolveSectorLeader(city);
        if (leader == null) {
            return null;
        }
        return formatLine(NamedTextColor.GREEN, "Sector: ", leader.name());
    }

    private List<String> formatConnectivityLines(City city) {
        List<String> lines = new ArrayList<>(2);
        if (linkService == null || !linkService.isEnabled() || city == null) {
            return lines;
        }
        int linkCount = Math.max(0, linkService.linkCount(city));
        if (linkCount <= 0) {
            return lines;
        }
        TrendDirection linkTrend = arrowForMetric(city, TrendUtil.Metric.LINKS, linkCount);
        lines.add(formatLineWithArrow(NamedTextColor.DARK_AQUA, "Links: ", String.valueOf(linkCount), linkTrend));

        long migrationNet = 0;
        boolean hasMigration = false;
        if (migrationService != null) {
            MigrationService.CityMigrationSnapshot snapshot = migrationService.snapshot(city);
            if (snapshot != null) {
                migrationNet = snapshot.net();
                hasMigration = migrationNet != 0;
            }
        }
        if (hasMigration) {
            TrendDirection migrationTrend = arrowForMetric(city, TrendUtil.Metric.MIGRATION, migrationNet);
            String value = migrationNet > 0 ? "+" + migrationNet : String.valueOf(migrationNet);
            lines.add(formatLineWithArrow(NamedTextColor.GRAY, "Migration: ", value, migrationTrend));
        }
        return lines;
    }

    private String formatLine(TextColor labelColor, String label, String value) {
        String safeValue = (value == null || value.isBlank()) ? "—" : value;
        Component component = Component.text(label, labelColor)
                .append(Component.text(safeValue, VALUE_COLOR));
        return LEGACY.serialize(component);
    }

    private String formatLineWithArrow(TextColor labelColor, String label, String value, TrendDirection trend) {
        String safeValue = (value == null || value.isBlank()) ? "—" : value;
        Component component = Component.text(label, labelColor)
                .append(Component.text(safeValue, VALUE_COLOR));
        if (trend != null && trend != TrendDirection.FLAT && !trend.glyph().isEmpty()) {
            TextColor arrowColor = trend == TrendDirection.UP ? NamedTextColor.GREEN : NamedTextColor.RED;
            component = component.append(Component.text(" " + trend.glyph(), arrowColor));
        }
        return LEGACY.serialize(component);
    }

    private TrendDirection arrowForMetric(City city, TrendUtil.Metric metric, double value) {
        if (city == null || city.id == null || !Double.isFinite(value)) {
            return TrendDirection.FLAT;
        }
        return trendUtil.trendFor(city.id, metric, value);
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

    private SectorLeader resolveSectorLeader(City city) {
        if (city == null || city.population <= 0 || city.isGhostTown()) {
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
            decorated.add(raw.get(i) + LEGACY_COLOR_CHAR + code);
        }
        return decorated;
    }

    private void applyLines(Objective objective, Scoreboard board, List<String> lines) {
        int score = lines.size();
        for (String line : lines) {
            objective.getScore(line).setScore(score--);
        }
    }

    private void addIfPresent(List<String> lines, String line) {
        if (line != null && !line.isBlank()) {
            lines.add(line);
        }
    }

    public void setMode(UUID uuid, Mode mode) {
        displayPreferencesStore.setScoreboardMode(uuid, mode);
    }

}
