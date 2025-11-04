package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.stats.EconomyBreakdown;
import dev.citysim.stats.EconomyBreakdownFormatter;
import dev.citysim.stats.HappinessBreakdown;
import dev.citysim.stats.HappinessBreakdownFormatter;
import dev.citysim.stats.HappinessBreakdownFormatter.ContributionLists;
import dev.citysim.stats.StationCountingMode;
import dev.citysim.stats.StatsService;
import dev.citysim.util.AdventureMessages;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

public class StatsCommand implements CitySubcommand {

    private final CityManager cityManager;
    private final StatsService statsService;

    public StatsCommand(CityManager cityManager, StatsService statsService) {
        this.cityManager = cityManager;
        this.statsService = statsService;
    }

    @Override
    public String name() {
        return "stats";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public List<Component> help() {
        return List.of(CommandMessages.help("/city stats [cityId]"));
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;

        City city = null;
        if (args.length >= 1) {
            city = cityManager.get(args[0]);
        }
        if (city == null) {
            city = cityManager.cityAt(player.getLocation());
        }
        if (city == null) {
            player.sendMessage(Component.text("Stand in a city or pass /city stats <cityId>", NamedTextColor.RED));
            return true;
        }

        var hb = statsService.updateCity(city, true);
        if (hb == null) {
            hb = new HappinessBreakdown();
        }
        hb.setGhostTown(hb.isGhostTown() || city.isGhostTown() || city.population <= 0);

        boolean showStations = statsService.getStationCountingMode() != StationCountingMode.DISABLED;
        String homesLine = "<blue>Homes:</blue> %d/%d".formatted(city.beds, city.population);
        if (showStations) {
            homesLine += "  <light_purple>Stations:</light_purple> %d".formatted(city.stations);
        }
        String mayorLine = formatMayorLine(city);

        String safeName = AdventureMessages.escapeMiniMessage(city.name);
        if (hb.isGhostTown()) {
            String msg = ("""
            <white><b>%s — City stats</b></white>
            %s
            <gold>Population:</gold> %d  <aqua>Employed:</aqua> %d  <red>Unemployed:</red> %d
            %s
            <gold>Prosperity:</gold> N/A (ghost town)
            %s
            """).formatted(
                    safeName, mayorLine, city.population, city.employed, city.unemployed,
                    homesLine,
                    hb.dominantMessage()
            ).stripTrailing();
            player.sendMessage(AdventureMessages.mini(msg));
            return true;
        }

        ContributionLists contributionLists = StatsFormatting.filterTransitIfHidden(statsService, HappinessBreakdownFormatter.buildContributionLists(hb));

        String breakdownLines = StatsFormatting.joinHappinessContributionLines(contributionLists.positives(), StatsFormatting::miniMessageLabelForHappiness);
        String negativeLines = StatsFormatting.joinHappinessContributionLines(contributionLists.negatives(), StatsFormatting::miniMessageLabelForHappiness);
        if (!negativeLines.isEmpty()) {
            if (!breakdownLines.isEmpty()) {
                breakdownLines += "\n";
            }
            breakdownLines += negativeLines;
        }

        EconomyBreakdown economyBreakdown = city.economyBreakdown;
        EconomyBreakdownFormatter.ContributionLists economyLists = EconomyBreakdownFormatter.buildContributionLists(economyBreakdown);
        String economyPositive = StatsFormatting.joinEconomyContributionLines(economyLists.positives(), StatsFormatting::miniMessageLabelForEconomy);
        String economyNegative = StatsFormatting.joinEconomyContributionLines(economyLists.negatives(), StatsFormatting::miniMessageLabelForEconomy);
        String economyLines = combineLines(economyPositive, economyNegative);

        int prosperityTotal = economyBreakdown != null ? economyBreakdown.total : hb.total;
        int prosperityBase = economyBreakdown != null ? economyBreakdown.base : hb.base;

        String gdpLine = formatGdpLine(city);
        String sectorLine = formatSectorLine(city);
        String pressureLine = formatPressureLine(city);
        String landValueLine = formatLandValueLine(city);

        String msg = ("""
        <white><b>%s — City stats</b></white>
        %s
        <gold>Population:</gold> %d  <aqua>Employed:</aqua> %d  <red>Unemployed:</red> %d
        %s
        <gold>Prosperity:</gold> %d%%  <white>(base %d)</white>
        %s
        <gray>Economy (Prosperity):</gray>
        %s
        %s
        %s
        %s
        %s
        """).formatted(
                safeName, mayorLine, city.population, city.employed, city.unemployed,
                homesLine,
                prosperityTotal,
                prosperityBase,
                breakdownLines,
                economyLines,
                gdpLine,
                sectorLine,
                pressureLine,
                landValueLine
        ).stripTrailing();
        player.sendMessage(AdventureMessages.mini(msg));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return cityManager.all().stream().map(c -> c.id).collect(Collectors.toList());
        }
        return List.of();
    }

    private String formatMayorLine(City city) {
        List<String> names = resolveMayorNames(city);
        if (names.isEmpty()) {
            return "<yellow>Mayors:</yellow> <gray>None assigned</gray>";
        }
        return "<yellow>Mayors:</yellow> " + String.join("<gray>, </gray>", names);
    }

    private List<String> resolveMayorNames(City city) {
        List<String> formatted = new ArrayList<>();
        if (city == null || city.mayors == null) {
            return formatted;
        }
        for (String raw : city.mayors) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String display = resolveMayorDisplay(raw);
            if (display == null || display.isBlank()) {
                continue;
            }
            formatted.add("<white>" + AdventureMessages.escapeMiniMessage(display) + "</white>");
        }
        return formatted;
    }

    private String resolveMayorDisplay(String raw) {
        try {
            UUID uuid = UUID.fromString(raw);
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            if (offline != null) {
                String name = offline.getName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
            return uuid.toString();
        } catch (IllegalArgumentException ex) {
            return raw;
        }
    }

    private String combineLines(String primary, String secondary) {
        if (primary.isEmpty()) {
            return secondary;
        }
        if (secondary.isEmpty()) {
            return primary;
        }
        return primary + "\n" + secondary;
    }

    private String formatGdpLine(City city) {
        String gdp = formatLargeNumber(city.gdp);
        String gdpPerCapita = formatLargeNumber(city.gdpPerCapita);
        return "<gold>GDP:</gold> %s  <aqua>Per capita:</aqua> %s".formatted(gdp, gdpPerCapita);
    }

    private String formatSectorLine(City city) {
        String agri = formatPercent(city.sectorAgri);
        String industry = formatPercent(city.sectorInd);
        String services = formatPercent(city.sectorServ);
        return "<green>Sectors:</green> Agri %s • Ind %s • Serv %s".formatted(agri, industry, services);
    }

    private String formatPressureLine(City city) {
        String jobs = formatSignedDelta(city.jobsPressure);
        String housing = formatSignedDelta(city.housingPressure);
        String transit = formatSignedDelta(city.transitPressure);
        return "<red>Pressures:</red> Jobs Δ: %s  Housing Δ: %s  Transit Δ: %s".formatted(jobs, housing, transit);
    }

    private String formatLandValueLine(City city) {
        return "<yellow>Land value:</yellow> %s".formatted(String.format(Locale.US, "%.1f", city.landValue));
    }

    private String formatLargeNumber(double value) {
        return String.format(Locale.US, "%,.1f", value);
    }

    private String formatPercent(double value) {
        return String.format(Locale.US, "%.0f%%", clamp01(value) * 100.0);
    }

    private String formatSignedDelta(double value) {
        String formatted = String.format(Locale.US, "%+.2f", value);
        if (formatted.startsWith("-")) {
            return "−" + formatted.substring(1);
        }
        return formatted;
    }

    private double clamp01(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
