package dev.citysim.cmd.subcommand;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.util.Collection;
import java.util.List;

public interface CitySubcommand {

    String name();

    default Collection<String> aliases() {
        return List.of();
    }

    default String permission() {
        return null;
    }

    default boolean playerOnly() {
        return false;
    }

    default boolean forcePlayerScan() {
        return false;
    }

    default String usage() {
        return null;
    }

    default List<Component> help() {
        return List.of();
    }

    boolean execute(CommandSender sender, String[] args);

    default List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
