package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.economy.CityEconomy;
import dev.citysim.economy.EconomyService;
import dev.citysim.economy.DistrictStats;
import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class EconomyCommand implements CitySubcommand {
    private final CityManager cityManager;
    private final EconomyService economyService;

    public EconomyCommand(CityManager cityManager, EconomyService economyService) {
        this.cityManager = cityManager;
        this.economyService = economyService;
    }

    @Override
    public String name() {
        return "economy";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public String permission() {
        return "citysim.economy";
    }

    @Override
    public List<Component> help() {
        return List.of(CommandMessages.help("/city economy [cityId]"));
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (economyService == null || !economyService.isEnabled()) {
            player.sendMessage(Component.text("Economy module disabled in configuration.", NamedTextColor.RED));
            return true;
        }

        City city = null;
        if (args.length >= 1) {
            city = cityManager.get(args[0]);
        }
        if (city == null) {
            city = cityManager.cityAt(player.getLocation());
        }
        if (city == null) {
            player.sendMessage(Component.text("Stand in a city or pass /city economy <cityId>.", NamedTextColor.RED));
            return true;
        }

        CityEconomy economy = economyService.economy(city.id);
        if (economy == null) {
            player.sendMessage(Component.text("Economy data is still loading. Try again shortly.", NamedTextColor.YELLOW));
            return true;
        }

        double avgLvi = economy.lviAverage();
        double gdp = economy.gdp();
        double gdpPerCapita = economy.gdpPerCapita();
        String lviTrend = trendArrow(economy.lviTrend());
        String gdpTrend = trendArrow(economy.gdpTrend());

        List<DistrictStats> sorted = economyService.sortedDistricts(city.id);
        String topSection = formatDistrictSlice(sorted.stream().limit(3).toList());
        List<DistrictStats> bottomList = new java.util.ArrayList<>(sorted);
        bottomList.sort((a, b) -> Double.compare(a.landValue0to100(), b.landValue0to100()));
        String bottomSection = formatDistrictSlice(bottomList.stream().limit(3).toList());

        String safeName = AdventureMessages.escapeMiniMessage(city.name);
        String message = ("""
        <white><b>%s — Economy</b></white>
        <gold>Avg Land Value:</gold> %.1f (%s)
        <aqua>GDP:</aqua> %.1f  <blue>GDP per villager:</blue> %.1f (%s)
        <green>Top districts:</green>
        %s
        <red>Bottom districts:</red>
        %s
        """).formatted(
                safeName,
                avgLvi,
                lviTrend,
                gdp,
                gdpPerCapita,
                gdpTrend,
                topSection.isEmpty() ? "<gray>(none)</gray>" : topSection,
                bottomSection.isEmpty() ? "<gray>(none)</gray>" : bottomSection
        ).stripTrailing();
        player.sendMessage(AdventureMessages.mini(message));
        return true;
    }

    private static String formatDistrictSlice(List<DistrictStats> stats) {
        if (stats.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (DistrictStats stat : stats) {
            builder.append(" - <white>")
                    .append(stat.key().chunkX()).append(',').append(stat.key().chunkZ())
                    .append("</white> LVI ")
                    .append(String.format(Locale.US, "%.1f", stat.landValue0to100()))
                    .append('\n');
        }
        return builder.toString().stripTrailing();
    }

    private static String trendArrow(CityEconomy.Trend trend) {
        if (trend == null) {
            return "→";
        }
        return switch (trend) {
            case UP -> "↑";
            case DOWN -> "↓";
            default -> "→";
        };
    }
}
