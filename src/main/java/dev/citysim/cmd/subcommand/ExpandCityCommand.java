package dev.citysim.cmd.subcommand;

import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandFeedback;
import dev.citysim.cmd.CommandMessages;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.stream.Collectors;

public class ExpandCityCommand implements CitySubcommand {

    private final CityManager cityManager;
    private final EditCityCommand editCityCommand;

    public ExpandCityCommand(CityManager cityManager, EditCityCommand editCityCommand) {
        this.cityManager = cityManager;
        this.editCityCommand = editCityCommand;
    }

    @Override
    public String name() {
        return "expand";
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
        return "/city expand <cityId>";
    }

    @Override
    public List<Component> help() {
        return List.of(CommandMessages.help("/city expand <cityId>"));
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            CommandFeedback.sendWarning(sender, "Usage: /city expand <cityId>");
            return true;
        }

        String cityId = args[0];
        return editCityCommand.handleCuboidAdd(sender, cityId);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return cityManager.all().stream().map(c -> c.id).collect(Collectors.toList());
        }
        return List.of();
    }
}
