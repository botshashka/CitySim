package dev.citysim.cmd;

import dev.citysim.CitySimPlugin;
import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.subcommand.CitySubcommand;
import dev.citysim.cmd.subcommand.CitySubcommandRegistry;
import dev.citysim.cmd.subcommand.CreateCityCommand;
import dev.citysim.cmd.subcommand.DebugCommand;
import dev.citysim.cmd.subcommand.DisplayCommand;
import dev.citysim.cmd.subcommand.EditCityCommand;
import dev.citysim.cmd.subcommand.ListCitiesCommand;
import dev.citysim.cmd.subcommand.ReloadCommand;
import dev.citysim.cmd.subcommand.RemoveCityCommand;
import dev.citysim.cmd.subcommand.StatsCommand;
import dev.citysim.cmd.subcommand.TopCommand;
import dev.citysim.cmd.subcommand.WandCommand;
import dev.citysim.stats.StatsService;
import dev.citysim.ui.ScoreboardService;
import dev.citysim.ui.TitleService;
import dev.citysim.stats.BossBarService;
import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class CityCommand implements CommandExecutor {

    private final CityManager cityManager;
    private final StatsService statsService;
    private final CitySubcommandRegistry registry = new CitySubcommandRegistry();

    public CityCommand(CitySimPlugin plugin,
                       CityManager cityManager,
                       StatsService statsService,
                       TitleService titleService,
                       BossBarService bossBarService,
                       ScoreboardService scoreboardService) {
        this.cityManager = cityManager;
        this.statsService = statsService;

        register(new WandCommand());
        register(new CreateCityCommand(cityManager, statsService));
        register(new ListCitiesCommand(cityManager));
        register(new RemoveCityCommand(cityManager));
        register(new EditCityCommand(cityManager, statsService));
        register(new StatsCommand(cityManager, statsService));
        register(new DisplayCommand(titleService, bossBarService, scoreboardService));
        register(new TopCommand(cityManager));
        register(new ReloadCommand(plugin));
        register(new DebugCommand(statsService));
    }

    public CitySubcommandRegistry getRegistry() {
        return registry;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            requestPlayerCityScan(sender, false);
            return sendHelp(sender);
        }

        String key = args[0].toLowerCase(Locale.ROOT);
        CitySubcommand subcommand = registry.get(key);
        if (subcommand == null) {
            requestPlayerCityScan(sender, false);
            return sendHelp(sender);
        }

        requestPlayerCityScan(sender, subcommand.forcePlayerScan());

        if (subcommand.permission() != null && !sender.hasPermission(subcommand.permission())) {
            CommandFeedback.sendNoPermission(sender);
            return true;
        }

        if (subcommand.playerOnly() && !(sender instanceof Player)) {
            CommandFeedback.sendPlayersOnly(sender);
            return true;
        }

        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        return subcommand.execute(sender, subArgs);
    }

    private void register(CitySubcommand subcommand) {
        registry.register(subcommand);
    }

    private boolean sendHelp(CommandSender sender) {
        List<Component> lines = new ArrayList<>();
        for (CitySubcommand sub : registry.all()) {
            lines.addAll(sub.help());
        }
        if (lines.isEmpty()) {
            return true;
        }
        sender.sendMessage(AdventureMessages.joinLines(lines.toArray(Component[]::new)));
        return true;
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
}
