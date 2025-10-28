package dev.citysim.cmd;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class CityTab implements TabCompleter {

    private final CityManager cityManager;

    public CityTab(CityManager cityManager) {
        this.cityManager = cityManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Subcommand (arg0)
        if (args.length == 1) {
            return filter(Arrays.asList(
                    "create",
                    "add",
                    "list",
                    "remove",
                    "delete",
                    "edit",
                    "wand",
                    "stats",
                    "titles",
                    "bossbar",
                    "scoreboard",
                    "ymode",
                    "top"
            ), args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "create":
            case "add":
                if (args.length == 2) {
                    return filter(Arrays.asList("<name>"), args[1]);
                }
                return List.of();

            case "list":
                return List.of();

            case "remove":
            case "delete":
                if (args.length == 2) {
                    List<String> ids = cityManager.all().stream().map(c -> c.id).collect(Collectors.toList());
                    return filter(ids, args[1]);
                }
                return List.of();

            case "edit":
                if (args.length == 2) {
                    List<String> ids = cityManager.all().stream().map(c -> c.id).collect(Collectors.toList());
                    return filter(ids, args[1]);
                }
                if (args.length == 3) {
                    return filter(Arrays.asList("name", "addcuboid", "removecuboid"), args[2]);
                }
                if (args.length >= 4 && "name".equalsIgnoreCase(args[2])) {
                    return filter(Arrays.asList("<new name>"), args[3]);
                }
                return List.of();

            case "wand":
                return List.of();

            case "stats":
                // /city stats [cityId]
                if (args.length == 2) {
                    List<String> ids = cityManager.all().stream().map(c -> c.id).collect(Collectors.toList());
                    return filter(ids, args[1]);
                }
                return List.of();

            case "titles":
                // /city titles on|off
                if (args.length == 2) {
                    return filter(Arrays.asList("on", "off"), args[1]);
                }
                return List.of();

            case "bossbar":
                if (args.length == 2) {
                    return filter(Arrays.asList("on", "off"), args[1]);
                }
                return List.of();

            case "scoreboard":
                // /city scoreboard on|off
                // /city scoreboard mode compact|full
                if (args.length == 2) {
                    return filter(Arrays.asList("on", "off", "mode"), args[1]);
                }
                if (args.length >= 3 && "mode".equalsIgnoreCase(args[1])) {
                    return filter(Arrays.asList("compact", "full"), args[2]);
                }
                return List.of();

            case "ymode":
                if (args.length == 2) {
                    return filter(Arrays.asList("full", "span"), args[1]);
                }
                return List.of();

            case "top":
                // /city top [happy|pop]
                if (args.length == 2) {
                    return filter(Arrays.asList("happy", "pop"), args[1]);
                }
                return List.of();

            default:
                return List.of();
        }
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : options) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        }
        return out;
    }
}
