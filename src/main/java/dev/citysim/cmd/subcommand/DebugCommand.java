package dev.citysim.cmd.subcommand;

import dev.citysim.cmd.CommandMessages;
import dev.citysim.stats.StatsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class DebugCommand implements CitySubcommand {

    private final StatsService statsService;

    public DebugCommand(StatsService statsService) {
        this.statsService = statsService;
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
        sendUsage(player);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("scans");
        }
        return List.of();
    }

    private void sendUsage(Player player) {
        player.sendMessage(CommandMessages.usage("Usage: /city debug scans"));
    }
}
