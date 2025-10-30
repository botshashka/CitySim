package dev.citysim.cmd;

import dev.citysim.cmd.subcommand.CitySubcommand;
import dev.citysim.cmd.subcommand.CitySubcommandRegistry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class CityTab implements TabCompleter {

    private final CitySubcommandRegistry registry;

    public CityTab(CitySubcommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            return List.of();
        }

        if (args.length == 1) {
            return filter(collectSubcommandKeys(), args[0]);
        }

        CitySubcommand subcommand = registry.get(args[0].toLowerCase(Locale.ROOT));
        if (subcommand == null) {
            return List.of();
        }

        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        List<String> completions = new ArrayList<>(subcommand.tabComplete(sender, subArgs));
        if (completions.isEmpty()) {
            return completions;
        }
        String prefix = subArgs.length == 0 ? "" : subArgs[subArgs.length - 1];
        return filter(completions, prefix);
    }

    private List<String> collectSubcommandKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (CitySubcommand sub : registry.all()) {
            keys.add(sub.name());
            for (String alias : sub.aliases()) {
                keys.add(alias);
            }
        }
        return new ArrayList<>(keys);
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(option);
            }
        }
        return out;
    }
}
