package dev.citysim.cmd;

import dev.citysim.CitySimPlugin;
import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import dev.citysim.selection.SelectionListener;
import dev.citysim.selection.SelectionState;
import dev.citysim.stats.BossBarService;
import dev.citysim.stats.StatsService;
import dev.citysim.ui.ScoreboardService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Consumer;

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
            case "ymode":
                return handleYMode(s, args);
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

        sender.sendMessage(ChatColor.GREEN + "CitySim configuration reloaded.");
        return true;
    }

    private boolean handleCreate(CommandSender sender, String[] args) {
        if (!checkAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /city create <name>");
            return true;
        }

        String name = joinArgs(args, 1);
        if (name.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "City name cannot be empty.");
            return true;
        }

        try {
            City created = cityManager.create(name);
            cityManager.save();
            statsService.requestCityUpdate(created, true);
            sender.sendMessage(ChatColor.GREEN + "Created new city " + created.name + " (ID: " + created.id + "). Use /city wand and /city edit " + created.id + " addcuboid to define its area.");
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ChatColor.RED + ex.getMessage());
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (cityManager.all().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No cities have been created yet.");
            return true;
        }

        sender.sendMessage(ChatColor.GRAY + "Cities:");
        for (City city : cityManager.all()) {
            sender.sendMessage(ChatColor.GOLD + city.id + ChatColor.GRAY + " — " + ChatColor.WHITE + city.name);
        }
        return true;
    }

    private boolean handleRemove(CommandSender sender, String[] args) {
        if (!checkAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /city remove <cityId>");
            return true;
        }

        String id = args[1];
        City removed = cityManager.remove(id);
        if (removed == null) {
            sender.sendMessage(ChatColor.RED + "City with id '" + id + "' does not exist.");
            return true;
        }

        cityManager.save();
        sender.sendMessage(ChatColor.GREEN + "City '" + removed.name + "' (ID: " + removed.id + ") removed.");
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
                sender.sendMessage(ChatColor.RED + "Unknown edit action. Use name, addcuboid, removecuboid, highrise, or station.");
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
            sender.sendMessage(ChatColor.RED + "New name cannot be empty.");
            return true;
        }

        try {
            City renamed = cityManager.rename(cityId, newName);
            cityManager.save();
        statsService.requestCityUpdate(renamed, true);
            sender.sendMessage(ChatColor.GREEN + "City renamed to " + renamed.name + " (ID: " + renamed.id + ").");
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ChatColor.RED + ex.getMessage());
        }
        return true;
    }

    private boolean handleAddCuboid(CommandSender sender, String cityId) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        SelectionState sel = SelectionListener.get(player);
        if (!sel.ready()) {
            player.sendMessage(ChatColor.RED + "You must select two corners with the CitySim wand first!");
            return true;
        }
        if (sel.world != sel.pos1.getWorld() || sel.world != sel.pos2.getWorld()) {
            player.sendMessage(ChatColor.RED + "Your selection must be in a single world.");
            return true;
        }
        if (sel.world != player.getWorld()) {
            player.sendMessage(ChatColor.RED + "You are in a different world than your selection.");
            return true;
        }

        City city = cityManager.get(cityId);
        if (city == null) {
            player.sendMessage(ChatColor.RED + "City with id '" + cityId + "' does not exist.");
            return true;
        }

        boolean fullHeight = sel.yMode == SelectionState.YMode.FULL;
        Cuboid cuboid = new Cuboid(sel.world, sel.pos1, sel.pos2, fullHeight);

        try {
            int index = cityManager.addCuboid(city.id, cuboid);
            cityManager.save();
        statsService.requestCityUpdate(city, true);

            int width = cuboid.maxX - cuboid.minX + 1;
            int length = cuboid.maxZ - cuboid.minZ + 1;
            int height = cuboid.maxY - cuboid.minY + 1;
            String mode = fullHeight ? "full" : "span";
            player.sendMessage(ChatColor.GREEN + "Added cuboid #" + index + " to " + city.name + " (" + width + "×" + length + "×" + height + ", mode: " + mode + ").");
        } catch (IllegalArgumentException ex) {
            player.sendMessage(ChatColor.RED + ex.getMessage());
        }
        return true;
    }

    private boolean handleRemoveCuboid(CommandSender sender, String cityId) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        City city = cityManager.get(cityId);
        if (city == null) {
            player.sendMessage(ChatColor.RED + "City with id '" + cityId + "' does not exist.");
            return true;
        }

        int removed;
        try {
            removed = cityManager.removeCuboidsContaining(city.id, player.getLocation());
        } catch (IllegalArgumentException ex) {
            player.sendMessage(ChatColor.RED + ex.getMessage());
            return true;
        }

        if (removed == 0) {
            player.sendMessage(ChatColor.YELLOW + "You are not standing inside any cuboids for " + city.name + ".");
            return true;
        }

        cityManager.save();
        statsService.requestCityUpdate(city, true);
        player.sendMessage(ChatColor.GREEN + "Removed " + removed + " cuboid" + (removed == 1 ? "" : "s") + " from " + city.name + ".");
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
            sender.sendMessage(ChatColor.RED + "Highrise value must be true/false.");
            return true;
        }

        City city = cityManager.get(cityId);
        if (city == null) {
            sender.sendMessage(ChatColor.RED + "City with id '" + cityId + "' does not exist.");
            return true;
        }

        try {
            cityManager.setHighrise(city.id, enable);
            cityManager.save();
        statsService.requestCityUpdate(city, true);
            sender.sendMessage(ChatColor.GREEN + "City '" + city.name + "' highrise set to " + enable + ".");
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ChatColor.RED + ex.getMessage());
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
            sender.sendMessage(ChatColor.RED + "City with id '" + cityId + "' does not exist.");
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
        statsService.requestCityUpdate(city, true);

        if (updated == previousStations) {
            String word = updated == 1 ? " station" : " stations";
            sender.sendMessage(ChatColor.YELLOW + "City '" + city.name + "' remains at " + updated + word + ".");
        } else {
            String newWord = updated == 1 ? " station" : " stations";
            String oldWord = previousStations == 1 ? " station" : " stations";
            sender.sendMessage(ChatColor.GREEN + "City '" + city.name + "' now has " + updated + newWord + " (was " + previousStations + oldWord + ").");
        }
        return true;
    }

    private boolean handleDebug(CommandSender sender, String[] args) {
        if (!checkAdmin(sender)) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
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
                player.sendMessage(ChatColor.GREEN + "City scan debug enabled. Scan activity will appear in this chat.");
            } else {
                player.sendMessage(ChatColor.YELLOW + "City scan debug disabled.");
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
            sender.sendMessage("Players only.");
            return true;
        }
        if (!checkAdmin(sender)) {
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
            SelectionListener.clear(player);
            player.sendMessage(ChatColor.GREEN + "Selection cleared.");
            return true;
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
        player.sendMessage(ChatColor.GREEN + "CitySim wand given. Left/right click blocks to set the selection.");
        return true;
    }

    private boolean handleStats(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
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
            player.sendMessage(ChatColor.RED + "Stand in a city or pass /city stats <cityId>");
            return true;
        }

        var hb = statsService.updateCity(city, true);
        var mm = MiniMessage.miniMessage();
        var extraParts = new java.util.ArrayList<String>(3);
        extraParts.add("<light_purple>Transit:</light_purple> %s"
                .formatted(String.format(Locale.US, "%+.1f", hb.transitPoints)));
        if (hb.overcrowdingPenalty > 0) {
            extraParts.add("<red>Overcrowding:</red> -%.1f".formatted(hb.overcrowdingPenalty));
        }
        if (hb.pollutionPenalty > 0) {
            extraParts.add("<red>Pollution:</red> -%.1f".formatted(hb.pollutionPenalty));
        }

        String breakdownLines = "<yellow>Light:</yellow> %.1f  <aqua>Employment:</aqua> %.1f  <green>Nature:</green> %.1f  <blue>Housing:</blue> %.1f"
                .formatted(hb.lightPoints, hb.employmentPoints, hb.naturePoints, hb.housingPoints);
        if (!extraParts.isEmpty()) {
            breakdownLines += "\n" + String.join("  ", extraParts);
        }

        String msg = """
        <gray><b>%s — City stats</b></gray>
        <gold>Population:</gold> %d  <aqua>Employed:</aqua> %d  <red>Unemployed:</red> %d
        <blue>Homes:</blue> %d/%d  <light_purple>Stations:</light_purple> %d
        <gold>Happiness:</gold> %d  <gray>(base 50)</gray>
        %s
        """.formatted(
                city.name, city.population, city.employed, city.unemployed,
                city.beds, city.population, city.stations,
                hb.total,
                breakdownLines
        );
        player.sendMessage(mm.deserialize(msg));
        return true;
    }

    private boolean handleDisplay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
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
                            player.sendMessage(on ? ChatColor.GREEN + "Enter titles enabled" : ChatColor.RED + "Enter titles disabled");
                        });
            case "bossbar":
                return handleDisplayToggle(player, args, "Usage: /city display bossbar on|off",
                        on -> {
                            plugin.getBossBarService().setEnabled(player, on);
                            player.sendMessage(on ? ChatColor.GREEN + "City bossbar enabled" : ChatColor.RED + "City bossbar disabled");
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
            player.sendMessage(ChatColor.YELLOW + usage);
            return true;
        }
        boolean on = args[2].equalsIgnoreCase("on");
        toggler.accept(on);
        return true;
    }

    private boolean handleScoreboardDisplay(Player player, String[] args) {
        if (args.length >= 3 && args[2].equalsIgnoreCase("mode")) {
            if (args.length < 4) {
                player.sendMessage(ChatColor.YELLOW + "Usage: /city display scoreboard mode compact|full");
                return true;
            }
            ScoreboardService.Mode mode = args[3].equalsIgnoreCase("full") ? ScoreboardService.Mode.FULL : ScoreboardService.Mode.COMPACT;
            plugin.getScoreboardService().setMode(player.getUniqueId(), mode);
            player.sendMessage(ChatColor.GRAY + "Scoreboard mode set to " + mode.name().toLowerCase(Locale.ROOT));
            return true;
        }

        if (args.length >= 3) {
            boolean on = args[2].equalsIgnoreCase("on");
            plugin.getScoreboardService().setEnabled(player, on);
            player.sendMessage(on ? ChatColor.GREEN + "Scoreboard enabled" : ChatColor.RED + "Scoreboard disabled");
            return true;
        }

        player.sendMessage(ChatColor.GRAY + "/city display scoreboard on|off");
        player.sendMessage(ChatColor.GRAY + "/city display scoreboard mode compact|full");
        return true;
    }

    private boolean handleYMode(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!checkAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /city ymode <full|span>");
            return true;
        }

        String modeArg = args[1].toLowerCase(Locale.ROOT);
        SelectionState sel = SelectionListener.get(player);
        switch (modeArg) {
            case "full":
                sel.yMode = SelectionState.YMode.FULL;
                player.sendMessage(ChatColor.GRAY + "Y-mode set to full.");
                break;
            case "span":
                sel.yMode = SelectionState.YMode.SPAN;
                player.sendMessage(ChatColor.GRAY + "Y-mode set to span.");
                break;
            default:
                player.sendMessage(ChatColor.RED + "Unknown mode. Use full or span.");
                break;
        }
        return true;
    }

    private boolean handleTop(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
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
        player.sendMessage(sb.toString());
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
        sender.sendMessage(ChatColor.RED + "You do not have permission to do that.");
        return false;
    }

    private Integer parseNonNegative(String value, CommandSender sender, String context) {
        int amount;
        try {
            amount = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            sender.sendMessage(ChatColor.RED + "Invalid number for " + context + ": " + value);
            return null;
        }
        if (amount < 0) {
            sender.sendMessage(ChatColor.RED + "Amount must be zero or positive.");
            return null;
        }
        return amount;
    }

    private void sendStationUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage: /city edit <cityId> station <add|remove|set|clear> [amount]");
    }

    private void sendEditUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage:");
        sender.sendMessage(ChatColor.YELLOW + "/city edit <cityId> name <new name>");
        sender.sendMessage(ChatColor.YELLOW + "/city edit <cityId> addcuboid");
        sender.sendMessage(ChatColor.YELLOW + "/city edit <cityId> removecuboid");
        sender.sendMessage(ChatColor.YELLOW + "/city edit <cityId> highrise <true|false>");
        sender.sendMessage(ChatColor.YELLOW + "/city edit <cityId> station <add|remove|set|clear> [amount]");
    }

    private void sendDisplayUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage:");
        sender.sendMessage(ChatColor.YELLOW + "/city display titles on|off");
        sender.sendMessage(ChatColor.YELLOW + "/city display bossbar on|off");
        sender.sendMessage(ChatColor.YELLOW + "/city display scoreboard on|off");
        sender.sendMessage(ChatColor.YELLOW + "/city display scoreboard mode compact|full");
    }

    private void sendDebugUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage:");
        sender.sendMessage(ChatColor.YELLOW + "/city debug scans");
    }

    private boolean help(CommandSender s) {
        s.sendMessage(ChatColor.GRAY + "/city wand [clear]");
        s.sendMessage(ChatColor.GRAY + "/city create <name>");
        s.sendMessage(ChatColor.GRAY + "/city add <name>");
        s.sendMessage(ChatColor.GRAY + "/city list");
        s.sendMessage(ChatColor.GRAY + "/city remove <cityId>");
        s.sendMessage(ChatColor.GRAY + "/city edit <cityId> name <new name>");
        s.sendMessage(ChatColor.GRAY + "/city edit <cityId> addcuboid");
        s.sendMessage(ChatColor.GRAY + "/city edit <cityId> removecuboid");
        s.sendMessage(ChatColor.GRAY + "/city edit <cityId> highrise <true|false>");
        s.sendMessage(ChatColor.GRAY + "/city edit <cityId> station <add|remove|set|clear> [amount]");
        s.sendMessage(ChatColor.GRAY + "/city ymode <full|span>");
        s.sendMessage(ChatColor.GRAY + "/city stats [cityId]");
        s.sendMessage(ChatColor.GRAY + "/city display titles on|off");
        s.sendMessage(ChatColor.GRAY + "/city display bossbar on|off");
        s.sendMessage(ChatColor.GRAY + "/city display scoreboard on|off");
        s.sendMessage(ChatColor.GRAY + "/city display scoreboard mode compact|full");
        s.sendMessage(ChatColor.GRAY + "/city top [happy|pop]");
        s.sendMessage(ChatColor.GRAY + "/city reload");
        s.sendMessage(ChatColor.GRAY + "/city debug scans");
        return true;
    }
}
