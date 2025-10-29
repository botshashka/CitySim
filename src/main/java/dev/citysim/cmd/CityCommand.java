package dev.citysim.cmd;

import dev.citysim.CitySimPlugin;
import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import dev.citysim.selection.SelectionListener;
import dev.citysim.selection.SelectionState;
import dev.citysim.stats.BossBarService;
import dev.citysim.stats.HappinessBreakdownFormatter;
import dev.citysim.stats.HappinessBreakdownFormatter.ContributionLine;
import dev.citysim.stats.HappinessBreakdownFormatter.ContributionLists;
import dev.citysim.stats.HappinessBreakdownFormatter.ContributionType;
import dev.citysim.stats.StationCountingMode;
import dev.citysim.stats.StatsService;
import dev.citysim.ui.ScoreboardService;
import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

public class CityCommand implements CommandExecutor {

    private final CitySimPlugin plugin;
    private final CityManager cityManager;
    private final StatsService statsService;

    public CityCommand(CitySimPlugin plugin, CityManager cityManager, StatsService statsService) {
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.statsService = statsService;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            requestPlayerCityScan(s, false);
            return help(s);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        requestPlayerCityScan(s, shouldForcePlayerScan(sub));
        switch (sub) {
            case "create":
            case "add":
                return handleCreate(s, args);
            case "list":
                return handleList(s);
            case "remove":
            case "delete":
                return handleRemove(s, args);
            case "edit":
                return handleEdit(s, args);
            case "wand":
                return handleWand(s, args);
            case "stats":
                return handleStats(s, args);
            case "display":
                return handleDisplay(s, args);
            case "top":
                return handleTop(s, args);
            case "reload":
                return handleReload(s);
            case "debug":
                return handleDebug(s, args);
            default:
                return help(s);
        }
    }

    private boolean handleReload(CommandSender sender) {
        if (!checkAdmin(sender)) {
            return true;
        }

        plugin.reloadConfig();
        StatsService stats = plugin.getStatsService();
        if (stats != null) {
            stats.restartTask();
        }

        BossBarService bossBars = plugin.getBossBarService();
        if (bossBars != null) {
            bossBars.restart();
        }

        sendSuccess(sender, "CitySim configuration reloaded.");
        return true;
    }

    private boolean handleCreate(CommandSender sender, String[] args) {
        if (!checkAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sendWarning(sender, "Usage: /city create <name>");
            return true;
        }

        String name = joinArgs(args, 1);
        if (name.isEmpty()) {
            sendError(sender, "City name cannot be empty.");
            return true;
        }

        Cuboid pendingCuboid = null;
        int pendingWidth = 0;
        int pendingLength = 0;
        int pendingHeight = 0;
        String pendingMode = null;

        if (sender instanceof Player player) {
            SelectionState sel = SelectionListener.get(player);
            if (sel.ready()) {
                if (sel.world != sel.pos1.getWorld() || sel.world != sel.pos2.getWorld()) {
                    player.sendMessage(Component.text("Your selection must be in a single world. Use /city wand clear and try again.", NamedTextColor.RED));
                    return true;
                }
                if (sel.world != player.getWorld()) {
                    player.sendMessage(Component.text("You are in a different world than your selection. Switch worlds or clear it with /city wand clear before creating a city.", NamedTextColor.RED));
                    return true;
                }

                boolean fullHeight = sel.yMode == SelectionState.YMode.FULL;
                pendingCuboid = new Cuboid(sel.world, sel.pos1, sel.pos2, fullHeight);
                pendingWidth = pendingCuboid.maxX - pendingCuboid.minX + 1;
                pendingLength = pendingCuboid.maxZ - pendingCuboid.minZ + 1;
                pendingHeight = pendingCuboid.maxY - pendingCuboid.minY + 1;
                pendingMode = fullHeight ? "full" : "span";
            }
        }

        try {
            City created = cityManager.create(name);
            if (pendingCuboid != null) {
                cityManager.addCuboid(created.id, pendingCuboid);
            }
            cityManager.save();
            statsService.updateCity(created, true);

            Component base = Component.text()
                    .append(Component.text("Created new ", NamedTextColor.GREEN))
                    .append(Component.text(pendingCuboid != null ? "city " : "empty city ", NamedTextColor.GREEN))
                    .append(Component.text(created.name, NamedTextColor.GREEN))
                    .append(Component.text(" (ID: " + created.id + ")", NamedTextColor.GREEN))
                    .build();
            if (pendingCuboid != null) {
                Component details = Component.text()
                        .append(Component.text(" with an initial cuboid (", NamedTextColor.GREEN))
                        .append(Component.text(pendingWidth + "×" + pendingLength + "×" + pendingHeight, NamedTextColor.GREEN))
                        .append(Component.text(", mode: " + pendingMode + "). Use /city edit " + created.id + " addcuboid to add more areas.", NamedTextColor.GREEN))
                        .build();
                sender.sendMessage(base.append(details));
            } else {
                Component details = Component.text(". Use /city wand and /city edit " + created.id + " addcuboid to define its area.", NamedTextColor.GREEN);
                sender.sendMessage(base.append(details));
            }
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (cityManager.all().isEmpty()) {
            sendWarning(sender, "No cities have been created yet.");
            return true;
        }

        sendGray(sender, "Cities:");
        for (City city : cityManager.all()) {
            sender.sendMessage(Component.text()
                    .append(Component.text(city.id, NamedTextColor.GOLD))
                    .append(Component.text(" — ", NamedTextColor.GRAY))
                    .append(Component.text(city.name, NamedTextColor.WHITE))
                    .build());
        }
        return true;
    }

    private boolean handleRemove(CommandSender sender, String[] args) {
        if (!checkAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sendWarning(sender, "Usage: /city remove <cityId>");
            return true;
        }

        String id = args[1];
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

    private boolean handleEdit(CommandSender sender, String[] args) {
        if (!checkAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            sendEditUsage(sender);
            return true;
        }

        String id = args[1];
        String action = args[2].toLowerCase(Locale.ROOT);

        switch (action) {
            case "name":
                return handleRename(sender, id, args);
            case "addcuboid":
                return handleAddCuboid(sender, id);
            case "removecuboid":
                return handleRemoveCuboid(sender, id);
            case "highrise":
                return handleHighrise(sender, id, args);
            case "station":
                return handleStation(sender, id, args);
            default:
                sendError(sender, "Unknown edit action. Use name, addcuboid, removecuboid, highrise, or station.");
                return true;
        }
    }

    private boolean handleRename(CommandSender sender, String cityId, String[] args) {
        if (args.length < 4) {
            sendEditUsage(sender);
            return true;
        }

        String newName = joinArgs(args, 3);
        if (newName.isEmpty()) {
            sendError(sender, "New name cannot be empty.");
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
            sender.sendMessage(Component.text("Players only."));
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
            sender.sendMessage(Component.text("Players only."));
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
                    .append(Component.text("You are not standing inside any cuboids for ", NamedTextColor.YELLOW))
                    .append(Component.text(city.name, NamedTextColor.YELLOW))
                    .append(Component.text(".", NamedTextColor.YELLOW))
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
        if (args.length < 4) {
            sendEditUsage(sender);
            return true;
        }

        String value = args[3];
        boolean enable;
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on")) {
            enable = true;
        } else if (value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off")) {
            enable = false;
        } else {
            sendError(sender, "Highrise value must be true/false.");
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
        if (args.length < 4) {
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
            sendError(sender, "Station counts are managed automatically by TrainCarts; manual edits are disabled.");
            return true;
        }
        if (mode == StationCountingMode.DISABLED) {
            sendError(sender, "Station tracking is disabled in the configuration.");
            return true;
        }

        String stationAction = args[3].toLowerCase(Locale.ROOT);
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
                    .append(Component.text("City '", NamedTextColor.YELLOW))
                    .append(Component.text(city.name, NamedTextColor.YELLOW))
                    .append(Component.text("' remains at ", NamedTextColor.YELLOW))
                    .append(Component.text(Integer.toString(updated), NamedTextColor.YELLOW))
                    .append(Component.text(word, NamedTextColor.YELLOW))
                    .append(Component.text(".", NamedTextColor.YELLOW))
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

    private boolean handleDebug(CommandSender sender, String[] args) {
        if (!checkAdmin(sender)) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only."));
            return true;
        }
        if (args.length < 2) {
            sendDebugUsage(sender);
            return true;
        }
        String target = args[1].toLowerCase(Locale.ROOT);
        if ("scans".equals(target)) {
            boolean enabled = statsService.toggleScanDebug(player);
            if (enabled) {
                player.sendMessage(Component.text("City scan debug enabled. Scan activity will appear in this chat.", NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("City scan debug disabled.", NamedTextColor.YELLOW));
            }
            return true;
        }
        sendDebugUsage(sender);
        return true;
    }

    private Integer modifyStations(CommandSender sender, String[] args, int base, String context, BiFunction<Integer, Integer, Integer> operation) {
        if (args.length < 5) {
            sendStationUsage(sender);
            return null;
        }
        Integer amount = parseNonNegative(args[4], sender, context);
        if (amount == null) {
            return null;
        }
        return operation.apply(base, amount);
    }

    private boolean handleWand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only."));
            return true;
        }
        if (!checkAdmin(sender)) {
            return true;
        }

        if (args.length >= 2) {
            String option = args[1].toLowerCase(Locale.ROOT);
            if (option.equals("clear")) {
                SelectionListener.clear(player);
                player.sendMessage(Component.text("Selection cleared.", NamedTextColor.GREEN));
                return true;
            }
            if (option.equals("ymode")) {
                return handleWandYMode(player, args);
            }
        }

        ItemStack wand = new ItemStack(SelectionListener.WAND);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(Component.text("CitySim Wand", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        meta.lore(java.util.List.of(
                Component.text("Left click: set corner 1", NamedTextColor.YELLOW),
                Component.text("Right click: set corner 2", NamedTextColor.YELLOW)
        ));
        wand.setItemMeta(meta);

        player.getInventory().addItem(wand);
        player.sendMessage(Component.text("CitySim wand given. Left/right click blocks to set the selection.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleStats(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only."));
            return true;
        }

        City city = null;
        if (args.length >= 2) {
            city = cityManager.get(args[1]);
        }
        if (city == null) {
            city = cityManager.cityAt(player.getLocation());
        }
        if (city == null) {
            player.sendMessage(Component.text("Stand in a city or pass /city stats <cityId>", NamedTextColor.RED));
            return true;
        }

        var hb = statsService.updateCity(city, true);
        ContributionLists contributionLists = filterTransitIfHidden(HappinessBreakdownFormatter.buildContributionLists(hb));

        String breakdownLines = joinContributionLines(contributionLists.positives(), this::miniMessageLabelFor);
        String negativeLines = joinContributionLines(contributionLists.negatives(), this::miniMessageLabelFor);
        if (!negativeLines.isEmpty()) {
            if (!breakdownLines.isEmpty()) {
                breakdownLines += "\n";
            }
            breakdownLines += negativeLines;
        }

        boolean showStations = statsService.getStationCountingMode() != StationCountingMode.DISABLED;
        String homesLine = "<blue>Homes:</blue> %d/%d".formatted(city.beds, city.population);
        if (showStations) {
            homesLine += "  <light_purple>Stations:</light_purple> %d".formatted(city.stations);
        }

        String msg = """
        <gray><b>%s — City stats</b></gray>
        <gold>Population:</gold> %d  <aqua>Employed:</aqua> %d  <red>Unemployed:</red> %d
        %s
        <gold>Happiness:</gold> %d%%  <gray>(base 50)</gray>
        %s
        """.formatted(
                city.name, city.population, city.employed, city.unemployed,
                homesLine,
                hb.total,
                breakdownLines
        );
        player.sendMessage(AdventureMessages.mini(msg));
        return true;
    }

    private String joinContributionLines(List<ContributionLine> lines, Function<ContributionType, String> labelProvider) {
        if (lines.isEmpty()) {
            return "";
        }

        List<String> parts = new ArrayList<>(lines.size());
        for (ContributionLine line : lines) {
            parts.add(formatContributionLine(line, labelProvider.apply(line.type())));
        }
        return String.join("  ", parts);
    }

    private String formatContributionLine(ContributionLine line, String label) {
        String pattern = line.alwaysShowSign() ? "%+.1f" : "%.1f";
        return "%s %s".formatted(label, String.format(Locale.US, pattern, line.value()));
    }

    private String miniMessageLabelFor(ContributionType type) {
        return switch (type) {
            case LIGHT -> "<yellow>Light:</yellow>";
            case EMPLOYMENT -> "<aqua>Employment:</aqua>";
            case NATURE -> "<green>Nature:</green>";
            case HOUSING -> "<blue>Housing:</blue>";
            case TRANSIT -> "<light_purple>Transit:</light_purple>";
            case OVERCROWDING -> "<red>Overcrowding:</red>";
            case POLLUTION -> "<red>Pollution:</red>";
        };
    }

    private ContributionLists filterTransitIfHidden(ContributionLists lists) {
        if (statsService.getStationCountingMode() != StationCountingMode.DISABLED) {
            return lists;
        }
        List<ContributionLine> positives = new ArrayList<>();
        for (ContributionLine line : lists.positives()) {
            if (line.type() != ContributionType.TRANSIT) {
                positives.add(line);
            }
        }
        List<ContributionLine> negatives = new ArrayList<>();
        for (ContributionLine line : lists.negatives()) {
            if (line.type() != ContributionType.TRANSIT) {
                negatives.add(line);
            }
        }
        return new ContributionLists(List.copyOf(positives), List.copyOf(negatives));
    }

    private boolean handleDisplay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only."));
            return true;
        }
        if (args.length < 2) {
            sendDisplayUsage(player);
            return true;
        }

        String type = args[1].toLowerCase(Locale.ROOT);
        switch (type) {
            case "titles":
                return handleDisplayToggle(player, args, "Usage: /city display titles on|off",
                        on -> {
                            plugin.getTitleService().setEnabled(player.getUniqueId(), on);
                            player.sendMessage(on
                                    ? Component.text("Enter titles enabled", NamedTextColor.GREEN)
                                    : Component.text("Enter titles disabled", NamedTextColor.RED));
                        });
            case "bossbar":
                return handleDisplayToggle(player, args, "Usage: /city display bossbar on|off",
                        on -> {
                            plugin.getBossBarService().setEnabled(player, on);
                            player.sendMessage(on
                                    ? Component.text("City bossbar enabled", NamedTextColor.GREEN)
                                    : Component.text("City bossbar disabled", NamedTextColor.RED));
                        });
            case "scoreboard":
                return handleScoreboardDisplay(player, args);
            default:
                sendDisplayUsage(player);
                return true;
        }
    }

    private boolean handleDisplayToggle(Player player, String[] args, String usage, Consumer<Boolean> toggler) {
        if (args.length < 3) {
            player.sendMessage(Component.text(usage, NamedTextColor.YELLOW));
            return true;
        }
        String option = args[2].toLowerCase(Locale.ROOT);
        switch (option) {
            case "on":
                toggler.accept(true);
                break;
            case "off":
                toggler.accept(false);
                break;
            default:
                player.sendMessage(Component.text(usage, NamedTextColor.YELLOW));
                return true;
        }
        return true;
    }

    private boolean handleScoreboardDisplay(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /city display scoreboard <off|compact|full>", NamedTextColor.YELLOW));
            return true;
        }

        String option = args[2].toLowerCase(Locale.ROOT);
        switch (option) {
            case "off":
                plugin.getScoreboardService().setEnabled(player, false);
                player.sendMessage(Component.text("Scoreboard disabled", NamedTextColor.RED));
                return true;
            case "compact":
            case "full":
                ScoreboardService.Mode mode = option.equals("full") ? ScoreboardService.Mode.FULL : ScoreboardService.Mode.COMPACT;
                plugin.getScoreboardService().setMode(player.getUniqueId(), mode);
                plugin.getScoreboardService().setEnabled(player, true);
                player.sendMessage(Component.text()
                        .append(Component.text("Scoreboard enabled (", NamedTextColor.GREEN))
                        .append(Component.text(option, NamedTextColor.GREEN))
                        .append(Component.text(" mode)", NamedTextColor.GREEN))
                        .build());
                return true;
            default:
                player.sendMessage(Component.text("Usage: /city display scoreboard <off|compact|full>", NamedTextColor.YELLOW));
                return true;
        }
    }

    private boolean handleWandYMode(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /city wand ymode <full|span>", NamedTextColor.YELLOW));
            return true;
        }

        String modeArg = args[2].toLowerCase(Locale.ROOT);
        SelectionState sel = SelectionListener.get(player);
        switch (modeArg) {
            case "full":
                sel.yMode = SelectionState.YMode.FULL;
                player.sendMessage(Component.text("Y-mode set to full.", NamedTextColor.GRAY));
                break;
            case "span":
                sel.yMode = SelectionState.YMode.SPAN;
                player.sendMessage(Component.text("Y-mode set to span.", NamedTextColor.GRAY));
                break;
            default:
                player.sendMessage(Component.text("Unknown mode. Use full or span.", NamedTextColor.RED));
                break;
        }
        return true;
    }

    private boolean handleTop(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only."));
            return true;
        }

        String metric = (args.length >= 2) ? args[1].toLowerCase(Locale.ROOT) : "happy";
        java.util.List<City> list = new java.util.ArrayList<>(cityManager.all());
        if (metric.startsWith("pop")) {
            list.sort((a, b) -> Integer.compare(b.population, a.population));
        } else {
            list.sort((a, b) -> Integer.compare(b.happiness, a.happiness));
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

    private String joinArgs(String[] args, int startIndex) {
        return String.join(" ", Arrays.copyOfRange(args, startIndex, args.length)).trim();
    }

    private boolean shouldForcePlayerScan(String subcommand) {
        return switch (subcommand) {
            case "create", "add", "remove", "delete", "edit" -> true;
            default -> false;
        };
    }

    private void requestPlayerCityScan(CommandSender sender, boolean force) {
        if (!(sender instanceof Player player)) {
            return;
        }
        City city = cityManager.cityAt(player.getLocation());
        if (city != null) {
            statsService.requestCityUpdate(city, force);
        }
    }

    private boolean checkAdmin(CommandSender sender) {
        if (sender.hasPermission("citysim.admin")) {
            return true;
        }
        sendError(sender, "You do not have permission to do that.");
        return false;
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
            sendError(sender, "Amount must be zero or positive.");
            return null;
        }
        return amount;
    }

    private void sendStationUsage(CommandSender sender) {
        sendWarning(sender, "Usage: /city edit <cityId> station <add|remove|set|clear> [amount]");
    }

    private void sendEditUsage(CommandSender sender) {
        sender.sendMessage(AdventureMessages.joinLines(
                AdventureMessages.colored("Usage:", NamedTextColor.YELLOW),
                AdventureMessages.colored("/city edit <cityId> name <new name>", NamedTextColor.YELLOW),
                AdventureMessages.colored("/city edit <cityId> addcuboid", NamedTextColor.YELLOW),
                AdventureMessages.colored("/city edit <cityId> removecuboid", NamedTextColor.YELLOW),
                AdventureMessages.colored("/city edit <cityId> highrise <true|false>", NamedTextColor.YELLOW),
                AdventureMessages.colored("/city edit <cityId> station <add|remove|set|clear> [amount]", NamedTextColor.YELLOW)
        ));
    }

    private void sendDisplayUsage(CommandSender sender) {
        sender.sendMessage(AdventureMessages.joinLines(
                AdventureMessages.colored("Usage:", NamedTextColor.YELLOW),
                AdventureMessages.colored("/city display titles on|off", NamedTextColor.YELLOW),
                AdventureMessages.colored("/city display bossbar on|off", NamedTextColor.YELLOW),
                AdventureMessages.colored("/city display scoreboard <off|compact|full>", NamedTextColor.YELLOW)
        ));
    }

    private void sendDebugUsage(CommandSender sender) {
        sender.sendMessage(AdventureMessages.joinLines(
                AdventureMessages.colored("Usage:", NamedTextColor.YELLOW),
                AdventureMessages.colored("/city debug scans", NamedTextColor.YELLOW)
        ));
    }

    private boolean help(CommandSender s) {
        s.sendMessage(AdventureMessages.joinLines(
                AdventureMessages.colored("/city wand [clear]", NamedTextColor.GRAY),
                AdventureMessages.colored("/city create <name>", NamedTextColor.GRAY),
                AdventureMessages.colored("/city add <name>", NamedTextColor.GRAY),
                AdventureMessages.colored("/city list", NamedTextColor.GRAY),
                AdventureMessages.colored("/city remove <cityId>", NamedTextColor.GRAY),
                AdventureMessages.colored("/city edit <cityId> name <new name>", NamedTextColor.GRAY),
                AdventureMessages.colored("/city edit <cityId> addcuboid", NamedTextColor.GRAY),
                AdventureMessages.colored("/city edit <cityId> removecuboid", NamedTextColor.GRAY),
                AdventureMessages.colored("/city edit <cityId> highrise <true|false>", NamedTextColor.GRAY),
                AdventureMessages.colored("/city edit <cityId> station <add|remove|set|clear> [amount]", NamedTextColor.GRAY),
                AdventureMessages.colored("/city wand ymode <full|span>", NamedTextColor.GRAY),
                AdventureMessages.colored("/city stats [cityId]", NamedTextColor.GRAY),
                AdventureMessages.colored("/city display titles on|off", NamedTextColor.GRAY),
                AdventureMessages.colored("/city display bossbar on|off", NamedTextColor.GRAY),
                AdventureMessages.colored("/city display scoreboard <off|compact|full>", NamedTextColor.GRAY),
                AdventureMessages.colored("/city top [happy|pop]", NamedTextColor.GRAY),
                AdventureMessages.colored("/city reload", NamedTextColor.GRAY),
                AdventureMessages.colored("/city debug scans", NamedTextColor.GRAY)
        ));
        return true;
    }

    private void sendSuccess(CommandSender sender, String message) {
        sender.sendMessage(AdventureMessages.colored(message, NamedTextColor.GREEN));
    }

    private void sendWarning(CommandSender sender, String message) {
        sender.sendMessage(AdventureMessages.colored(message, NamedTextColor.YELLOW));
    }

    private void sendError(CommandSender sender, String message) {
        sender.sendMessage(AdventureMessages.colored(message, NamedTextColor.RED));
    }

    private void sendGray(CommandSender sender, String message) {
        sender.sendMessage(AdventureMessages.colored(message, NamedTextColor.GRAY));
    }
}
