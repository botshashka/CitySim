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
        help.add(CommandMessages.help("/city debug scans"));
        help.add(CommandMessages.help("/city debug set-trust <0-100> [cityId]"));
        help.add(CommandMessages.help("/city debug tickbudget [cityId]"));
        if (migrationService != null) {
            help.add(CommandMessages.help("/city debug migration"));
        }
        return help;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length < 1) {
            sendUsage(player);
            return true;
        }
        String target = args[0].toLowerCase();
        if ("scans".equals(target)) {
            boolean enabled = statsService.toggleScanDebug(player);
            if (enabled) {
                player.sendMessage(Component.text("City scan debug enabled. Scan activity will appear in this chat.", NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("City scan debug disabled.", NamedTextColor.YELLOW));
            }
            return true;
        }
        if ("set-trust".equals(target) || "settrust".equals(target)) {
            if (args.length < 2) {
                player.sendMessage(CommandMessages.usage("Usage: /city debug set-trust <0-100> [cityId]"));
                return true;
            }
            int value;
            try {
                value = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                player.sendMessage(Component.text("Trust must be a number between 0 and 100.", NamedTextColor.RED));
                return true;
            }
            value = Math.max(0, Math.min(100, value));

            City city = resolveCity(player, args.length >= 3 ? args[2] : null);
            if (city == null) {
                player.sendMessage(Component.text("Stand in a city or provide /city debug set-trust <value> <cityId>", NamedTextColor.RED));
                return true;
            }
            city.trust = value;
            if (budgetService != null) {
                budgetService.invalidatePreview(city);
            }
            player.sendMessage(Component.text("Set trust for " + city.name + " to " + value + ".", NamedTextColor.GREEN));
            return true;
        }
        if ("tickbudget".equals(target)) {
            City city = resolveCity(player, args.length >= 2 ? args[1] : null);
            if (city == null) {
                player.sendMessage(Component.text("Stand in a city or provide /city debug tickbudget <cityId>", NamedTextColor.RED));
                return true;
            }
            if (budgetService == null) {
                player.sendMessage(Component.text("Budget service unavailable.", NamedTextColor.RED));
                return true;
            }
            budgetService.tickCity(city);
            player.sendMessage(Component.text("Forced budget tick for " + city.name + ".", NamedTextColor.GREEN));
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
        sendUsage(player);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> options = migrationService != null
                    ? List.of("scans", "set-trust", "tickbudget", "migration")
                    : List.of("scans", "set-trust", "tickbudget");
            String prefix = args[0].toLowerCase();
            List<String> matches = new ArrayList<>();
            for (String option : options) {
                if (option.startsWith(prefix)) {
                    matches.add(option);
                }
            }
            return matches;
        }
        if (args.length == 2 && ("set-trust".equalsIgnoreCase(args[0]) || "settrust".equalsIgnoreCase(args[0]))) {
            return List.of("60");
        }
        if (args.length == 3 && ("set-trust".equalsIgnoreCase(args[0]) || "settrust".equalsIgnoreCase(args[0]))) {
            return cityManager.all().stream().map(c -> c.id).toList();
        }
        if (args.length == 2 && "tickbudget".equalsIgnoreCase(args[0])) {
            return cityManager.all().stream().map(c -> c.id).toList();
        }
        return List.of();
    }

    private void sendUsage(Player player) {
        player.sendMessage(CommandMessages.usage("Usage: /city debug <scans|set-trust|tickbudget|migration>"));
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
}
