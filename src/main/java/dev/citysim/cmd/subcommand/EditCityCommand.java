package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import dev.citysim.cmd.CommandFeedback;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.selection.SelectionListener;
import dev.citysim.selection.SelectionState;
import dev.citysim.stats.StationCountingMode;
import dev.citysim.stats.StatsService;
import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class EditCityCommand implements CitySubcommand {

    private static final List<Component> HELP = List.of(
            CommandMessages.help("/city edit <cityId> name <new name>"),
            CommandMessages.help("/city edit <cityId> addcuboid"),
            CommandMessages.help("/city edit <cityId> removecuboid"),
            CommandMessages.help("/city edit <cityId> highrise <true|false>"),
            CommandMessages.help("/city edit <cityId> station <add|remove|set|clear> [amount]")
    );

    private final CityManager cityManager;
    private final StatsService statsService;

    public EditCityCommand(CityManager cityManager, StatsService statsService) {
        this.cityManager = cityManager;
        this.statsService = statsService;
    }

    @Override
    public String name() {
        return "edit";
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
        return "/city edit <cityId> <action>";
    }

    @Override
    public List<Component> help() {
        return HELP;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendEditUsage(sender);
            return true;
        }

        String cityId = args[0];
        String action = args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "name" -> handleRename(sender, cityId, args);
            case "addcuboid" -> handleAddCuboid(sender, cityId);
            case "removecuboid" -> handleRemoveCuboid(sender, cityId);
            case "highrise" -> handleHighrise(sender, cityId, args);
            case "station" -> handleStation(sender, cityId, args);
            default -> {
                CommandFeedback.sendError(sender, "Unknown edit action. Use name, addcuboid, removecuboid, highrise, or station.");
                yield true;
            }
        };
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return cityManager.all().stream().map(c -> c.id).collect(Collectors.toList());
        }
        if (args.length == 2) {
            return List.of("name", "addcuboid", "removecuboid", "highrise", "station");
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if ("name".equals(action)) {
            if (args.length == 3) {
                return List.of("<new name>");
            }
            return List.of();
        }
        if ("highrise".equals(action) && args.length == 3) {
            return List.of("true", "false");
        }
        if ("station".equals(action)) {
            if (args.length == 3) {
                return List.of("add", "remove", "set", "clear");
            }
            if (args.length == 4) {
                String sub = args[2].toLowerCase(Locale.ROOT);
                if (List.of("add", "remove", "set").contains(sub)) {
                    return List.of("<amount>");
                }
            }
        }
        return List.of();
    }

    private boolean handleRename(CommandSender sender, String cityId, String[] args) {
        if (args.length < 3) {
            sendEditUsage(sender);
            return true;
        }

        String newName = joinArgs(Arrays.copyOfRange(args, 2, args.length));
        if (newName.isEmpty()) {
            CommandFeedback.sendError(sender, "New name cannot be empty.");
            return true;
        }

        try {
            City renamed = cityManager.rename(cityId, newName);
            cityManager.save();
            statsService.updateCity(renamed, true);
            sender.sendMessage(Component.text()
                    .append(Component.text("City renamed to ", NamedTextColor.GREEN))
                    .append(Component.text(renamed.name, NamedTextColor.GREEN))
                    .append(Component.text(" (ID: " + renamed.id + ").", NamedTextColor.GREEN))
                    .build());
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    private boolean handleAddCuboid(CommandSender sender, String cityId) {
        if (!(sender instanceof Player player)) {
            CommandFeedback.sendPlayersOnly(sender);
            return true;
        }

        SelectionState sel = SelectionListener.get(player);
        if (!sel.ready()) {
            player.sendMessage(Component.text("You must select two corners with the CitySim wand first!", NamedTextColor.RED));
            return true;
        }
        if (sel.world != sel.pos1.getWorld() || sel.world != sel.pos2.getWorld()) {
            player.sendMessage(Component.text("Your selection must be in a single world.", NamedTextColor.RED));
            return true;
        }
        if (sel.world != player.getWorld()) {
            player.sendMessage(Component.text("You are in a different world than your selection.", NamedTextColor.RED));
            return true;
        }

        City city = cityManager.get(cityId);
        if (city == null) {
            player.sendMessage(Component.text()
                    .append(Component.text("City with id '", NamedTextColor.RED))
                    .append(Component.text(cityId, NamedTextColor.RED))
                    .append(Component.text("' does not exist.", NamedTextColor.RED))
                    .build());
            return true;
        }

        boolean fullHeight = sel.yMode == SelectionState.YMode.FULL;
        Cuboid cuboid = new Cuboid(sel.world, sel.pos1, sel.pos2, fullHeight);

        try {
            int index = cityManager.addCuboid(city.id, cuboid);
            cityManager.save();
            statsService.updateCity(city, true);

            int width = cuboid.maxX - cuboid.minX + 1;
            int length = cuboid.maxZ - cuboid.minZ + 1;
            int height = cuboid.maxY - cuboid.minY + 1;
            String mode = fullHeight ? "full" : "span";
            Component message = Component.text()
                    .append(Component.text("Added cuboid #", NamedTextColor.GREEN))
                    .append(Component.text(Integer.toString(index), NamedTextColor.GREEN))
                    .append(Component.text(" to ", NamedTextColor.GREEN))
                    .append(Component.text(city.name, NamedTextColor.GREEN))
                    .append(Component.text(" (" + width + "×" + length + "×" + height + ", mode: " + mode + ").", NamedTextColor.GREEN))
                    .build();
            player.sendMessage(message);
        } catch (IllegalArgumentException ex) {
            player.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    private boolean handleRemoveCuboid(CommandSender sender, String cityId) {
        if (!(sender instanceof Player player)) {
            CommandFeedback.sendPlayersOnly(sender);
            return true;
        }

        City city = cityManager.get(cityId);
        if (city == null) {
            player.sendMessage(Component.text()
                    .append(Component.text("City with id '", NamedTextColor.RED))
                    .append(Component.text(cityId, NamedTextColor.RED))
                    .append(Component.text("' does not exist.", NamedTextColor.RED))
                    .build());
            return true;
        }

        int removed;
        try {
            removed = cityManager.removeCuboidsContaining(city.id, player.getLocation());
        } catch (IllegalArgumentException ex) {
            player.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
            return true;
        }

        if (removed == 0) {
            player.sendMessage(Component.text()
                    .append(Component.text("You are not standing inside any cuboids for ", NamedTextColor.WHITE))
                    .append(Component.text(city.name, NamedTextColor.WHITE))
                    .append(Component.text(".", NamedTextColor.WHITE))
                    .build());
            return true;
        }

        cityManager.save();
        statsService.updateCity(city, true);
        String suffix = removed == 1 ? " cuboid" : " cuboids";
        player.sendMessage(Component.text()
                .append(Component.text("Removed ", NamedTextColor.GREEN))
                .append(Component.text(Integer.toString(removed), NamedTextColor.GREEN))
                .append(Component.text(suffix, NamedTextColor.GREEN))
                .append(Component.text(" from ", NamedTextColor.GREEN))
                .append(Component.text(city.name, NamedTextColor.GREEN))
                .append(Component.text(".", NamedTextColor.GREEN))
                .build());
        return true;
    }

    private boolean handleHighrise(CommandSender sender, String cityId, String[] args) {
        if (args.length < 3) {
            sendEditUsage(sender);
            return true;
        }

        String value = args[2];
        Boolean enable = parseBoolean(value);
        if (enable == null) {
            CommandFeedback.sendError(sender, "Highrise value must be true/false.");
            return true;
        }

        City city = cityManager.get(cityId);
        if (city == null) {
            sender.sendMessage(Component.text()
                    .append(Component.text("City with id '", NamedTextColor.RED))
                    .append(Component.text(cityId, NamedTextColor.RED))
                    .append(Component.text("' does not exist.", NamedTextColor.RED))
                    .build());
            return true;
        }

        try {
            cityManager.setHighrise(city.id, enable);
            cityManager.save();
            statsService.updateCity(city, true);
            sender.sendMessage(Component.text()
                    .append(Component.text("City '", NamedTextColor.GREEN))
                    .append(Component.text(city.name, NamedTextColor.GREEN))
                    .append(Component.text("' highrise set to ", NamedTextColor.GREEN))
                    .append(Component.text(Boolean.toString(enable), NamedTextColor.GREEN))
                    .append(Component.text(".", NamedTextColor.GREEN))
                    .build());
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    private boolean handleStation(CommandSender sender, String cityId, String[] args) {
        if (args.length < 3) {
            sendStationUsage(sender);
            return true;
        }

        City city = cityManager.get(cityId);
        if (city == null) {
            sender.sendMessage(Component.text()
                    .append(Component.text("City with id '", NamedTextColor.RED))
                    .append(Component.text(cityId, NamedTextColor.RED))
                    .append(Component.text("' does not exist.", NamedTextColor.RED))
                    .build());
            return true;
        }

        StationCountingMode mode = statsService.getStationCountingMode();
        if (mode == StationCountingMode.TRAIN_CARTS) {
            CommandFeedback.sendError(sender, "Station counts are managed automatically by TrainCarts; manual edits are disabled.");
            return true;
        }
        if (mode == StationCountingMode.DISABLED) {
            CommandFeedback.sendError(sender, "Station tracking is disabled in the configuration.");
            return true;
        }

        String stationAction = args[2].toLowerCase(Locale.ROOT);
        int previousStations = Math.max(0, city.stations);

        Integer updated = switch (stationAction) {
            case "add" -> modifyStations(sender, args, previousStations, "stations to add", Integer::sum);
            case "remove" -> modifyStations(sender, args, previousStations, "stations to remove", (a, b) -> Math.max(0, a - b));
            case "set" -> modifyStations(sender, args, previousStations, "station count", (a, b) -> b);
            case "clear" -> 0;
            default -> {
                sendStationUsage(sender);
                yield null;
            }
        };

        if (updated == null) {
            return true;
        }

        if (updated < 0) {
            updated = 0;
        }

        city.stations = updated;
        cityManager.save();
        statsService.updateCity(city, true);

        if (updated == previousStations) {
            String word = updated == 1 ? " station" : " stations";
            sender.sendMessage(Component.text()
                    .append(Component.text("City '", NamedTextColor.WHITE))
                    .append(Component.text(city.name, NamedTextColor.WHITE))
                    .append(Component.text("' remains at ", NamedTextColor.WHITE))
                    .append(Component.text(Integer.toString(updated), NamedTextColor.WHITE))
                    .append(Component.text(word, NamedTextColor.WHITE))
                    .append(Component.text(".", NamedTextColor.WHITE))
                    .build());
        } else {
            String newWord = updated == 1 ? " station" : " stations";
            String oldWord = previousStations == 1 ? " station" : " stations";
            sender.sendMessage(Component.text()
                    .append(Component.text("City '", NamedTextColor.GREEN))
                    .append(Component.text(city.name, NamedTextColor.GREEN))
                    .append(Component.text("' now has ", NamedTextColor.GREEN))
                    .append(Component.text(Integer.toString(updated), NamedTextColor.GREEN))
                    .append(Component.text(newWord, NamedTextColor.GREEN))
                    .append(Component.text(" (was ", NamedTextColor.GREEN))
                    .append(Component.text(Integer.toString(previousStations), NamedTextColor.GREEN))
                    .append(Component.text(oldWord, NamedTextColor.GREEN))
                    .append(Component.text(").", NamedTextColor.GREEN))
                    .build());
        }
        return true;
    }

    private Integer modifyStations(CommandSender sender, String[] args, int base, String context, StationOperator operator) {
        if (args.length < 4) {
            sendStationUsage(sender);
            return null;
        }
        Integer amount = parseNonNegative(args[3], sender, context);
        if (amount == null) {
            return null;
        }
        return operator.apply(base, amount);
    }

    private Integer parseNonNegative(String value, CommandSender sender, String context) {
        int amount;
        try {
            amount = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Component.text()
                    .append(Component.text("Invalid number for ", NamedTextColor.RED))
                    .append(Component.text(context, NamedTextColor.RED))
                    .append(Component.text(": ", NamedTextColor.RED))
                    .append(Component.text(value, NamedTextColor.RED))
                    .build());
            return null;
        }
        if (amount < 0) {
            CommandFeedback.sendError(sender, "Amount must be zero or positive.");
            return null;
        }
        return amount;
    }

    private void sendStationUsage(CommandSender sender) {
        sender.sendMessage(AdventureMessages.joinLines(
                CommandMessages.usage("Usage:"),
                CommandMessages.usage("/city edit <cityId> station <add|remove|set|clear> [amount]")
        ));
    }

    private void sendEditUsage(CommandSender sender) {
        List<Component> lines = new ArrayList<>(HELP.size() + 1);
        lines.add(CommandMessages.usage("Usage:"));
        lines.addAll(HELP);
        sender.sendMessage(AdventureMessages.joinLines(lines.toArray(Component[]::new)));
    }

    private String joinArgs(String[] args) {
        return String.join(" ", args).trim();
    }

    private Boolean parseBoolean(String value) {
        if (value == null) {
            return null;
        }
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on")) {
            return true;
        }
        if (value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off")) {
            return false;
        }
        return null;
    }

    @FunctionalInterface
    private interface StationOperator {
        int apply(int base, int amount);
    }
}
