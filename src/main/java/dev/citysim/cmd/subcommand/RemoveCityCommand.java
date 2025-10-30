package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandFeedback;
import dev.citysim.cmd.CommandMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class RemoveCityCommand implements CitySubcommand {

    private final CityManager cityManager;

    public RemoveCityCommand(CityManager cityManager) {
        this.cityManager = cityManager;
    }

    @Override
    public String name() {
        return "remove";
    }

    @Override
    public Collection<String> aliases() {
        return List.of("delete");
    }

    @Override
    public String permission() {
        return "citysim.admin";
    }

    @Override
    public boolean forcePlayerScan() {
        return true;
    }

    @Override
    public String usage() {
        return "/city remove <cityId>";
    }

    @Override
    public List<Component> help() {
        return List.of(
                CommandMessages.help("/city remove <cityId>"),
                CommandMessages.help("/city delete <cityId>")
        );
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            CommandFeedback.sendWarning(sender, "Usage: /city remove <cityId>");
            return true;
        }

        String id = args[0];
        City removed = cityManager.remove(id);
        if (removed == null) {
            sender.sendMessage(Component.text()
                    .append(Component.text("City with id '", NamedTextColor.RED))
                    .append(Component.text(id, NamedTextColor.RED))
                    .append(Component.text("' does not exist.", NamedTextColor.RED))
                    .build());
            return true;
        }

        cityManager.save();
        sender.sendMessage(Component.text()
                .append(Component.text("City '", NamedTextColor.GREEN))
                .append(Component.text(removed.name, NamedTextColor.GREEN))
                .append(Component.text("' (ID: " + removed.id + ") removed.", NamedTextColor.GREEN))
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
