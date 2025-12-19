package dev.citysim.cmd.subcommand;

import dev.citysim.cmd.CommandMessages;
import dev.citysim.budget.BudgetService;
import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.migration.MigrationService;
import dev.citysim.stats.StatsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class DebugCommand implements CitySubcommand {

    private final CityManager cityManager;
    private final StatsService statsService;
    private final MigrationService migrationService;
    private final BudgetService budgetService;

    public DebugCommand(CityManager cityManager, StatsService statsService, MigrationService migrationService, BudgetService budgetService) {
        this.cityManager = cityManager;
        this.statsService = statsService;
        this.migrationService = migrationService;
        this.budgetService = budgetService;
    }

    @Override
    public String name() {
        return "debug";
    }

    @Override
    public String permission() {
        return "citysim.admin";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public List<Component> help() {
        List<Component> help = new ArrayList<>();
        help.add(CommandMessages.help("/city debug show scans"));
        if (migrationService != null) {
            help.add(CommandMessages.help("/city debug show migration"));
        }
        help.add(CommandMessages.help("/city debug set trust <0-100> [cityId|*>]"));
        help.add(CommandMessages.help("/city debug set budget <amount> [cityId|*>]"));
        help.add(CommandMessages.help("/city debug tick budget [cityId|*>]"));
        return help;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length < 1) {
            sendUsage(player);
            return true;
        }
        String top = args[0].toLowerCase();
        switch (top) {
            case "show" -> {
                return handleShow(player, args);
            }
            case "set" -> {
                return handleSet(player, args);
            }
            case "tick" -> {
                return handleTick(player, args);
            }
            default -> {
                sendUsage(player);
                return true;
            }
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("show", "set", "tick");
        }
        if (args.length == 2) {
            String first = args[0].toLowerCase();
            if ("show".equals(first)) {
                List<String> opts = new ArrayList<>();
                opts.add("scans");
                if (migrationService != null) {
                    opts.add("migration");
                }
                return filterByPrefix(opts, args[1]);
            }
            if ("set".equals(first)) {
                return filterByPrefix(List.of("trust", "budget"), args[1]);
            }
            if ("tick".equals(first)) {
                return filterByPrefix(List.of("budget"), args[1]);
            }
        }
        if (args.length == 3 && "set".equalsIgnoreCase(args[0])) {
            if ("trust".equalsIgnoreCase(args[1])) {
                return List.of("60");
            }
            if ("budget".equalsIgnoreCase(args[1])) {
                return List.of("1000");
            }
        }
        if (args.length == 4 && "set".equalsIgnoreCase(args[0]) && ("trust".equalsIgnoreCase(args[1]) || "budget".equalsIgnoreCase(args[1]))) {
            List<String> ids = cityManager.all().stream().map(c -> c.id).toList();
            List<String> withAll = new ArrayList<>(ids);
            withAll.add("*");
            return withAll;
        }
        if (args.length == 3 && "tick".equalsIgnoreCase(args[0]) && "budget".equalsIgnoreCase(args[1])) {
            List<String> ids = cityManager.all().stream().map(c -> c.id).toList();
            List<String> withAll = new ArrayList<>(ids);
            withAll.add("*");
            return withAll;
        }
        return List.of();
    }

    private void sendUsage(Player player) {
        player.sendMessage(CommandMessages.usage("Usage: /city debug <show|set|tick> ..."));
    }

    private City resolveCity(Player player, String cityId) {
        City city = null;
        if (cityId != null && !cityId.isBlank()) {
            city = cityManager.get(cityId);
        }
        if (city == null) {
            city = cityManager.cityAt(player.getLocation());
        }
        return city;
    }

    private boolean handleShow(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(CommandMessages.usage("Usage: /city debug show <scans|migration>"));
            return true;
        }
        String target = args[1].toLowerCase();
        if ("scans".equals(target)) {
            boolean enabled = statsService.toggleScanDebug(player);
            if (enabled) {
                player.sendMessage(Component.text("City scan debug enabled. Scan activity will appear in this chat.", NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("City scan debug disabled.", NamedTextColor.YELLOW));
            }
            return true;
        }
        if ("migration".equals(target)) {
            if (migrationService == null) {
                player.sendMessage(Component.text("Migration debug is unavailable on this server.", NamedTextColor.RED));
                return true;
            }
            boolean enabled = migrationService.toggleDebug(player);
            if (enabled) {
                player.sendMessage(Component.text("Migration debug enabled. Migration activity will appear in this chat.", NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("Migration debug disabled.", NamedTextColor.YELLOW));
            }
            return true;
        }
        player.sendMessage(CommandMessages.usage("Usage: /city debug show <scans|migration>"));
        return true;
    }

    private boolean handleSet(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(CommandMessages.usage("Usage: /city debug set <trust|budget> <value> [cityId]"));
            return true;
        }
        String target = args[1].toLowerCase();
        if ("trust".equals(target)) {
            int value;
            try {
                value = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                player.sendMessage(Component.text("Trust must be a number between 0 and 100.", NamedTextColor.RED));
                return true;
            }
            value = Math.max(0, Math.min(100, value));

            if (args.length >= 4 && "*".equals(args[3])) {
                int count = 0;
                for (City c : cityManager.all()) {
                    if (c == null) continue;
                    c.trust = value;
                    if (budgetService != null) {
                        budgetService.invalidatePreview(c);
                    }
                    count++;
                }
                player.sendMessage(Component.text("Set trust for " + count + " cities to " + value + ".", NamedTextColor.GREEN));
                return true;
            }
            City city = resolveCity(player, args.length >= 4 ? args[3] : null);
            if (city == null) {
                player.sendMessage(Component.text("Stand in a city or provide /city debug set trust <value> <cityId|*>", NamedTextColor.RED));
                return true;
            }
            city.trust = value;
            if (budgetService != null) {
                budgetService.invalidatePreview(city);
            }
            player.sendMessage(Component.text("Set trust for " + city.name + " to " + value + ".", NamedTextColor.GREEN));
            return true;
        }
        if ("budget".equals(target)) {
            double value;
            try {
                value = Double.parseDouble(args[2]);
            } catch (NumberFormatException ex) {
                player.sendMessage(Component.text("Budget amount must be a number.", NamedTextColor.RED));
                return true;
            }
            if (args.length >= 4 && "*".equals(args[3])) {
                int count = 0;
                for (City c : cityManager.all()) {
                    if (c == null) continue;
                    c.treasury = value;
                    if (budgetService != null) {
                        budgetService.invalidatePreview(c);
                    }
                    count++;
                }
                player.sendMessage(Component.text("Set treasury for " + count + " cities to " + value + ".", NamedTextColor.GREEN));
                return true;
            }
            City city = resolveCity(player, args.length >= 4 ? args[3] : null);
            if (city == null) {
                player.sendMessage(Component.text("Stand in a city or provide /city debug set budget <amount> <cityId|*>", NamedTextColor.RED));
                return true;
            }
            city.treasury = value;
            if (budgetService != null) {
                budgetService.invalidatePreview(city);
            }
            player.sendMessage(Component.text("Set treasury for " + city.name + " to " + value + ".", NamedTextColor.GREEN));
            return true;
        }
        player.sendMessage(CommandMessages.usage("Usage: /city debug set <trust|budget> <value> [cityId]"));
        return true;
    }

    private boolean handleTick(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(CommandMessages.usage("Usage: /city debug tick budget [cityId]"));
            return true;
        }
        String target = args[1].toLowerCase();
        if (!"budget".equals(target)) {
            player.sendMessage(CommandMessages.usage("Usage: /city debug tick budget [cityId]"));
            return true;
        }
        if (budgetService == null) {
            player.sendMessage(Component.text("Budget service unavailable.", NamedTextColor.RED));
            return true;
        }
        if (args.length >= 3 && "*".equals(args[2])) {
            int count = 0;
            for (City c : cityManager.all()) {
                if (c == null) continue;
                budgetService.tickCity(c);
                count++;
            }
            player.sendMessage(Component.text("Forced budget tick for " + count + " cities.", NamedTextColor.GREEN));
            return true;
        }
        City city = resolveCity(player, args.length >= 3 ? args[2] : null);
        if (city == null) {
            player.sendMessage(Component.text("Stand in a city or provide /city debug tick budget <cityId|*>", NamedTextColor.RED));
            return true;
        }
        budgetService.tickCity(city);
        player.sendMessage(Component.text("Forced budget tick for " + city.name + ".", NamedTextColor.GREEN));
        return true;
    }

    private List<String> filterByPrefix(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> matches = new ArrayList<>();
        for (String opt : options) {
            if (opt.startsWith(lower)) {
                matches.add(opt);
            }
        }
        return matches;
    }
}
