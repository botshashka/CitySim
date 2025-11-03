package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.economy.CityEconomy;
import dev.citysim.economy.EconomyService;
import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MarketCommand implements CitySubcommand {

    private final CityManager cityManager;
    private final EconomyService economyService;

    public MarketCommand(CityManager cityManager, EconomyService economyService) {
        this.cityManager = cityManager;
        this.economyService = economyService;
    }

    @Override
    public String name() {
        return "market";
    }

    @Override
    public List<Component> help() {
        return List.of(
                CommandMessages.help("/city market top"),
                CommandMessages.help("/city market losers"),
                CommandMessages.help("/city market calm")
        );
    }

    @Override
    public String permission() {
        return "citysim.command.market";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!economyService.settings().enabled()) {
            sender.sendMessage(Component.text("The economy module is disabled in config.", NamedTextColor.RED));
            return true;
        }
        String mode = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "top";
        List<CityEntry> entries = collectEntries();
        if (entries.isEmpty()) {
            sender.sendMessage(Component.text("No market data yet.", NamedTextColor.YELLOW));
            return true;
        }
        String header;
        String body;
        switch (mode) {
            case "losers" -> {
                entries.sort(Comparator.comparingDouble(e -> e.economy.indexPrice()));
                header = "<white><b>City market — daily losers</b></white>";
                body = formatIndex(entries, false);
            }
            case "calm" -> {
                entries.sort(Comparator.comparingDouble(e -> Math.sqrt(Math.max(0.0D, e.economy.volatilityEma()))));
                header = "<white><b>City market — calmest cities</b></white>";
                body = formatVolatility(entries);
            }
            case "top" -> {
                entries.sort(Comparator.comparingDouble((CityEntry e) -> e.economy.indexPrice()).reversed());
                header = "<white><b>City market — top performers</b></white>";
                body = formatIndex(entries, true);
            }
            default -> {
                sender.sendMessage(Component.text("Unknown market subcommand.", NamedTextColor.RED));
                return true;
            }
        }
        sender.sendMessage(AdventureMessages.mini(header + "\n" + body));
        return true;
    }

    private List<CityEntry> collectEntries() {
        List<CityEntry> entries = new ArrayList<>();
        for (City city : cityManager.all()) {
            if (city == null || city.id == null) {
                continue;
            }
            CityEconomy economy = economyService.cityEconomy(city.id);
            if (economy == null) {
                continue;
            }
            entries.add(new CityEntry(city, economy));
        }
        return entries;
    }

    private String formatIndex(List<CityEntry> entries, boolean descending) {
        int limit = Math.min(5, entries.size());
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            CityEntry entry = entries.get(i);
            double indexPrice = entry.economy.indexPrice();
            double delta = (indexPrice / 100.0D) - 1.0D;
            if (!descending) {
                delta = -delta;
            }
            if (i > 0) {
                builder.append('\n');
            }
            builder.append("<gray>#").append(i + 1).append("</gray> ")
                    .append("<white>").append(AdventureMessages.escapeMiniMessage(entry.city.name)).append("</white> — ")
                    .append(String.format(Locale.US, "%.1f", indexPrice))
                    .append(" <gray>(Δ ")
                    .append(String.format(Locale.US, "%+.2f%%", delta * 100.0D))
                    .append(")</gray>");
        }
        return builder.toString();
    }

    private String formatVolatility(List<CityEntry> entries) {
        int limit = Math.min(5, entries.size());
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            CityEntry entry = entries.get(i);
            double volatility = Math.sqrt(Math.max(0.0D, entry.economy.volatilityEma()));
            if (i > 0) {
                builder.append('\n');
            }
            builder.append("<gray>#").append(i + 1).append("</gray> ")
                    .append("<white>").append(AdventureMessages.escapeMiniMessage(entry.city.name)).append("</white> — ")
                    .append(String.format(Locale.US, "%.4f", volatility));
        }
        return builder.toString();
    }

    private record CityEntry(City city, CityEconomy economy) { }
}
