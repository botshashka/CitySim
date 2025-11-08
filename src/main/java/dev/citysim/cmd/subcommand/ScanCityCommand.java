package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandFeedback;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.stats.ProsperityBreakdown;
import dev.citysim.stats.StatsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

public class ScanCityCommand implements CitySubcommand {

    private final CityManager cityManager;
    private final StatsService statsService;

    public ScanCityCommand(CityManager cityManager, StatsService statsService) {
        this.cityManager = cityManager;
        this.statsService = statsService;
    }

    @Override
    public String name() {
        return "scan";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public List<Component> help() {
        return List.of(CommandMessages.help("/city scan [cityId]"));
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
            CommandFeedback.sendWarning(player, "Stand in a city or pass /city scan <cityId>");
            return true;
        }

        ProsperityBreakdown result = statsService.updateCity(city, true);
        if (result == null) {
            player.sendMessage(Component.text("Failed to scan city '" + city.name + "'. Check logs for details.", NamedTextColor.RED));
            return true;
        }
        player.sendMessage(Component.text()
                .append(Component.text("Scanned ", NamedTextColor.GREEN))
                .append(Component.text(city.name, NamedTextColor.AQUA))
                .append(Component.text(" successfully.", NamedTextColor.GREEN))
                .build());
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
