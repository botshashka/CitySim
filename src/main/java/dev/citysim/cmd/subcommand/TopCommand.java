package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class TopCommand implements CitySubcommand {

    private final CityManager cityManager;

    public TopCommand(CityManager cityManager) {
        this.cityManager = cityManager;
    }

    @Override
    public String name() {
        return "top";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public List<Component> help() {
        return List.of(CommandMessages.help("/city top [prosperity|pop]"));
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        String metric = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "prosperity";

        List<City> list = new ArrayList<>(cityManager.all());
        Comparator<City> comparator;
        if (metric.startsWith("pop")) {
            comparator = Comparator
                    .comparing(City::isGhostTown)
                    .thenComparing(Comparator.comparingInt((City c) -> c.population).reversed())
                    .thenComparing(c -> c.name, String.CASE_INSENSITIVE_ORDER);
        } else {
            comparator = Comparator
                    .comparing(City::isGhostTown)
                    .thenComparing(Comparator.comparingInt((City c) -> c.prosperity).reversed())
                    .thenComparing(c -> c.name, String.CASE_INSENSITIVE_ORDER);
        }
        list.sort(comparator);

        int limit = Math.min(10, list.size());
        StringBuilder sb = new StringBuilder();
        sb.append("Top cities by ").append(metric.startsWith("pop") ? "population" : "prosperity").append(":\n");
        for (int i = 0; i < limit; i++) {
            City city = list.get(i);
            String details;
            if (city.isGhostTown()) {
                details = "pop %d".formatted(city.population);
            } else {
                String prosperityDisplay = city.prosperity + "%";
                details = "pop %d, prosperity %s".formatted(city.population, prosperityDisplay);
            }
            sb.append(String.format("%2d. %s  —  %s\n", i + 1, city.name, details));
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        player.sendMessage(Component.text(sb.toString(), NamedTextColor.WHITE));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("prosperity", "pop");
        }
        return List.of();
    }
}
