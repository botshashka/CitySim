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
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class MarketCommand implements CitySubcommand {
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
    public boolean playerOnly() {
        return true;
    }

    @Override
    public String permission() {
        return "citysim.market";
    }

    @Override
    public List<Component> help() {
        return List.of(CommandMessages.help("/city market <top|losers|calm>"));
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (economyService == null || !economyService.isEnabled()) {
            player.sendMessage(Component.text("Economy module disabled in configuration.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /city market <top|losers|calm>", NamedTextColor.RED));
            return true;
        }
        String mode = args[0].toLowerCase(Locale.ROOT);
        List<City> cities = cityManager.all().stream()
                .filter(c -> c != null && c.id != null)
                .collect(Collectors.toList());
        if (cities.isEmpty()) {
            player.sendMessage(Component.text("No cities available.", NamedTextColor.YELLOW));
            return true;
        }

        switch (mode) {
            case "top" -> sendRanking(player, cities, Comparator.comparingDouble(this::indexReturn).reversed(), "Market leaders");
            case "losers" -> sendRanking(player, cities, Comparator.comparingDouble(this::indexReturn), "Market laggards");
            case "calm" -> sendRanking(player, cities, Comparator.comparingDouble(this::volatility), "Calm cities");
            default -> player.sendMessage(Component.text("Unknown market mode.", NamedTextColor.RED));
        }
        return true;
    }

    private void sendRanking(Player player, List<City> cities, Comparator<City> comparator, String title) {
        List<City> ranked = cities.stream()
                .filter(c -> economyService.economy(c.id) != null)
                .sorted(comparator)
                .limit(10)
                .collect(Collectors.toList());
        if (ranked.isEmpty()) {
            player.sendMessage(Component.text("No market data yet.", NamedTextColor.YELLOW));
            return;
        }
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < ranked.size(); i++) {
            City city = ranked.get(i);
            CityEconomy economy = economyService.economy(city.id);
            double index = economy != null ? economy.indexPrice() : 0.0;
            double change = indexReturn(city);
            double calm = volatility(city);
            body.append(i + 1).append(". <white>")
                    .append(AdventureMessages.escapeMiniMessage(city.name))
                    .append("</white>  <aqua>index </aqua>")
                    .append(String.format(Locale.US, "%.1f", index))
                    .append("  <green>return </green>")
                    .append(String.format(Locale.US, "%.3f", change))
                    .append("  <gray>calm </gray>")
                    .append(String.format(Locale.US, "%.3f", calm))
                    .append('\n');
        }
        String message = ("""
        <white><b>%s</b></white>
        %s
        """).formatted(title, body.toString().stripTrailing()).stripTrailing();
        player.sendMessage(AdventureMessages.mini(message));
    }

    private double indexReturn(City city) {
        CityEconomy economy = economyService.economy(city.id);
        if (economy == null) {
            return 0.0;
        }
        return (economy.indexPrice() - 100.0) / 100.0;
    }

    private double volatility(City city) {
        CityEconomy economy = economyService.economy(city.id);
        if (economy == null) {
            return Double.MAX_VALUE;
        }
        return economy.volatilityEma();
    }
}
