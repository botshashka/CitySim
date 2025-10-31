package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandFeedback;
import dev.citysim.cmd.CommandMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.List;

public class ListCitiesCommand implements CitySubcommand {

    private final CityManager cityManager;

    public ListCitiesCommand(CityManager cityManager) {
        this.cityManager = cityManager;
    }

    @Override
    public String name() {
        return "list";
    }

    @Override
    public List<Component> help() {
        return List.of(CommandMessages.help("/city list"));
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (cityManager.all().isEmpty()) {
            CommandFeedback.sendWarning(sender, "No cities have been created yet.");
            return true;
        }

        CommandFeedback.sendInfo(sender, "Cities:");
        for (City city : cityManager.all()) {
            sender.sendMessage(Component.text()
                    .append(Component.text(city.id, NamedTextColor.GOLD))
                    .append(Component.text(" — ", NamedTextColor.WHITE))
                    .append(Component.text(city.name, NamedTextColor.WHITE))
                    .build());
        }
        return true;
    }
}
