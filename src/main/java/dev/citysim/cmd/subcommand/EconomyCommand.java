package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.economy.CityEconomy;
import dev.citysim.economy.DistrictKey;
import dev.citysim.economy.DistrictStats;
import dev.citysim.economy.EconomyService;
import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class EconomyCommand implements CitySubcommand {

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
        return "citysim.command.economy";
    }

    @Override
    public List<Component> help() {
        return List.of(CommandMessages.help("/city economy [cityId]"));
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (!economyService.settings().enabled()) {
            player.sendMessage(Component.text("The economy module is disabled in config.", NamedTextColor.RED));
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
            player.sendMessage(Component.text("Stand in a city or pass /city economy <cityId>", NamedTextColor.RED));
            return true;
        }

        Map<DistrictKey, DistrictStats> districts = economyService.grid(city.id);
        CityEconomy economy = economyService.cityEconomy(city.id);
        if (economy == null || districts.isEmpty()) {
            player.sendMessage(Component.text("Economy data is still warming up. Try again soon.", NamedTextColor.YELLOW));
            return true;
        }

        double avgLvi = economy.lviAverage();
        String lviTrend = trendArrow(economy.lviAverage() - economy.lviEma());
        double gdp = economy.gdp();
        double gdpPerCapita = economy.gdpPerCapita();
        String gdpTrend = trendArrow(economy.gdpReturnEma());

        List<Map.Entry<DistrictKey, DistrictStats>> entries = new ArrayList<>(districts.entrySet());
        entries.sort(Comparator.comparingDouble(e -> -e.getValue().landValueRaw()));
        String topLines = formatDistrictList(entries, 3, true);
        String bottomLines = formatDistrictList(entries, 3, false);

        String safeName = AdventureMessages.escapeMiniMessage(city.name);
        String message = ("""
        <white><b>%s — Economy</b></white>
        <gold>Avg Land Value:</gold> %.1f (%s)
        <gold>GDP:</gold> %.1f  <gray>GDP per villager:</gray> %.1f (%s)
        <yellow>Top districts:</yellow>
        %s
        <yellow>Bottom districts:</yellow>
        %s
        """).formatted(
                safeName,
                avgLvi,
                lviTrend,
                gdp,
                gdpPerCapita,
                gdpTrend,
                topLines,
                bottomLines
        ).stripTrailing();

        player.sendMessage(AdventureMessages.mini(message));
        return true;
    }

    private String formatDistrictList(List<Map.Entry<DistrictKey, DistrictStats>> entries, int limit, boolean top) {
        if (entries.isEmpty()) {
            return "<gray>No data</gray>";
        }
        StringBuilder builder = new StringBuilder();
        int count = Math.min(limit, entries.size());
        if (!top) {
            entries = new ArrayList<>(entries);
            entries.sort(Comparator.comparingDouble(e -> e.getValue().landValueRaw()));
        }
        for (int i = 0; i < count; i++) {
            Map.Entry<DistrictKey, DistrictStats> entry = entries.get(i);
            DistrictKey key = entry.getKey();
            DistrictStats stats = entry.getValue();
            if (i > 0) {
                builder.append('\n');
            }
            builder.append("<gray>#").append(i + 1).append("</gray> ")
                    .append("<white>(")
                    .append(key.chunkX()).append(',').append(key.chunkZ()).append(")</white> ")
                    .append("<gold>LVI</gold> ")
                    .append(String.format(Locale.US, "%.1f", stats.landValueRaw()));
        }
        return builder.toString();
    }

    private String trendArrow(double delta) {
        if (delta > 0.01) {
            return "<green>↑</green>";
        }
        if (delta < -0.01) {
            return "<red>↓</red>";
        }
        return "<gray>→</gray>";
    }
}
