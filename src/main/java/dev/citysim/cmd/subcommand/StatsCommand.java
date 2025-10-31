package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.stats.HappinessBreakdown;
import dev.citysim.stats.HappinessBreakdownFormatter;
import dev.citysim.stats.HappinessBreakdownFormatter.ContributionLists;
import dev.citysim.stats.StationCountingMode;
import dev.citysim.stats.StatsService;
import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
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

        String safeName = AdventureMessages.escapeMiniMessage(city.name);
        if (hb.isGhostTown()) {
            String msg = ("""
            <white><b>%s — City stats</b></white>
            <gold>Population:</gold> %d  <aqua>Employed:</aqua> %d  <red>Unemployed:</red> %d
            %s
            <gold>Happiness:</gold> N/A (ghost town)
            %s
            """).formatted(
                    safeName, city.population, city.employed, city.unemployed,
                    homesLine,
                    hb.dominantMessage()
            ).stripTrailing();
            player.sendMessage(AdventureMessages.mini(msg));
            return true;
        }

        ContributionLists contributionLists = StatsFormatting.filterTransitIfHidden(statsService, HappinessBreakdownFormatter.buildContributionLists(hb));

        String breakdownLines = StatsFormatting.joinContributionLines(contributionLists.positives(), StatsFormatting::miniMessageLabelFor);
        String negativeLines = StatsFormatting.joinContributionLines(contributionLists.negatives(), StatsFormatting::miniMessageLabelFor);
        if (!negativeLines.isEmpty()) {
            if (!breakdownLines.isEmpty()) {
                breakdownLines += "\n";
            }
            breakdownLines += negativeLines;
        }

        String msg = ("""
        <white><b>%s — City stats</b></white>
        <gold>Population:</gold> %d  <aqua>Employed:</aqua> %d  <red>Unemployed:</red> %d
        %s
        <gold>Happiness:</gold> %d%%  <white>(base 50)</white>
        %s
        """).formatted(
                safeName, city.population, city.employed, city.unemployed,
                homesLine,
                hb.total,
                breakdownLines
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
}
