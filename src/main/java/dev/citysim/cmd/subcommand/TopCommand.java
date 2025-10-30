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
        return List.of(CommandMessages.help("/city top [happy|pop]"));
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        String metric = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "happy";

        List<City> list = new ArrayList<>(cityManager.all());
        if (metric.startsWith("pop")) {
            list.sort(Comparator.comparingInt((City c) -> c.population).reversed());
        } else {
            list.sort(Comparator.comparingInt((City c) -> c.happiness).reversed());
        }

        int limit = Math.min(10, list.size());
        StringBuilder sb = new StringBuilder();
        sb.append("Top cities by ").append(metric.startsWith("pop") ? "population" : "happiness").append(":\n");
        for (int i = 0; i < limit; i++) {
            City city = list.get(i);
            sb.append(String.format("%2d. %s  —  pop %d, happy %d\n", i + 1, city.name, city.population, city.happiness));
        }
        player.sendMessage(Component.text(sb.toString(), NamedTextColor.GRAY));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("happy", "pop");
        }
        return List.of();
    }
}
