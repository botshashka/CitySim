package dev.citysim.cmd.subcommand;

import dev.citysim.CitySimPlugin;
import dev.citysim.cmd.CommandFeedback;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.links.LinkService;
import dev.citysim.stats.BossBarService;
import dev.citysim.stats.StatsService;
import dev.citysim.visual.VisualizationService;
import dev.citysim.visual.VisualizationSettings;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.util.List;

public class ReloadCommand implements CitySubcommand {

    private final CitySimPlugin plugin;

    public ReloadCommand(CitySimPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String permission() {
        return "citysim.admin";
    }

    @Override
    public List<Component> help() {
        return List.of(CommandMessages.help("/city reload"));
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        plugin.reloadConfig();
        StatsService stats = plugin.getStatsService();
        if (stats != null) {
            stats.restartTask();
        }

        LinkService linkService = plugin.getLinkService();
        if (linkService != null) {
            linkService.reload(plugin.getConfig());
        }

        BossBarService bossBars = plugin.getBossBarService();
        if (bossBars != null) {
            bossBars.restart();
        }

        VisualizationService visualizationService = plugin.getVisualizationService();
        if (visualizationService != null) {
            visualizationService.reload(VisualizationSettings.fromConfig(plugin.getConfig()));
        }

        CommandFeedback.sendSuccess(sender, "CitySim configuration reloaded.");
        return true;
    }
}
