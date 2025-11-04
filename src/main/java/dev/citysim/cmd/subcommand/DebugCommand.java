package dev.citysim.cmd.subcommand;

import dev.citysim.cmd.CommandMessages;
import dev.citysim.migration.MigrationService;
import dev.citysim.stats.StatsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class DebugCommand implements CitySubcommand {

    private final StatsService statsService;
    private final MigrationService migrationService;

    public DebugCommand(StatsService statsService, MigrationService migrationService) {
        this.statsService = statsService;
        this.migrationService = migrationService;
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
        if (migrationService != null) {
            return List.of(
                    CommandMessages.help("/city debug scans"),
                    CommandMessages.help("/city debug migration")
            );
        }
        return List.of(CommandMessages.help("/city debug scans"));
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
                    ? List.of("scans", "migration")
                    : List.of("scans");
            String prefix = args[0].toLowerCase();
            List<String> matches = new ArrayList<>();
            for (String option : options) {
                if (option.startsWith(prefix)) {
                    matches.add(option);
                }
            }
            return matches;
        }
        return List.of();
    }

    private void sendUsage(Player player) {
        if (migrationService != null) {
            player.sendMessage(CommandMessages.usage("Usage: /city debug <scans|migration>"));
        } else {
            player.sendMessage(CommandMessages.usage("Usage: /city debug scans"));
        }
    }
}
