package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandFeedback;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.links.CityLink;
import dev.citysim.links.LinkService;
import dev.citysim.migration.MigrationService;
import dev.citysim.migration.MigrationService.CityMigrationSnapshot;
import dev.citysim.migration.MigrationService.LinkMigrationSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class LinksCommand implements CitySubcommand {

    private static final int DISPLAY_LIMIT = 10;

    private final CityManager cityManager;
    private final LinkService linkService;
    private final MigrationService migrationService;

    public LinksCommand(CityManager cityManager, LinkService linkService, MigrationService migrationService) {
        this.cityManager = cityManager;
        this.linkService = linkService;
        this.migrationService = migrationService;
    }

    @Override
    public String name() {
        return "links";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public List<Component> help() {
        return List.of(CommandMessages.help("/city links [cityId]"));
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (linkService == null || !linkService.isEnabled()) {
            CommandFeedback.sendWarning(player, "Links disabled.");
            return true;
        }

        City city = null;
        if (args.length >= 1) {
            city = cityManager.get(args[0]);
        }
        if (city == null) {
            city = cityManager.cityAt(player.getLocation());
        }
        if (city == null) {
            CommandFeedback.sendWarning(player, "Stand in a city or pass /city links <cityId>.");
            return true;
        }

        List<CityLink> links = linkService.computeLinks(city);
        if (links.isEmpty()) {
            CommandFeedback.sendInfo(player, "No linked cities found for " + city.name + ".");
            return true;
        }

        List<CityLink> display = links.size() <= DISPLAY_LIMIT ? links : links.subList(0, DISPLAY_LIMIT);
        CityMigrationSnapshot snapshot = migrationService != null ? migrationService.snapshot(city) : new CityMigrationSnapshot(0L, 0L, Map.of());
        CommandFeedback.sendInfo(player, "City links for " + city.name + " (" + links.size() + "):");
        for (CityLink link : display) {
            City neighbor = link.neighbor();
            String distance = String.format(Locale.US, "%.1f", link.distance());
            player.sendMessage(Component.text()
                    .append(Component.text("Neighbor: ", NamedTextColor.GOLD))
                    .append(Component.text(neighbor.name, NamedTextColor.WHITE))
                    .append(Component.text("  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Strength: ", NamedTextColor.LIGHT_PURPLE))
                    .append(Component.text("raw " + link.rawStrength() + " | operational " + link.opsStrength(), NamedTextColor.WHITE))
                    .append(Component.text("  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Distance: ", NamedTextColor.AQUA))
                    .append(Component.text(distance + "m", NamedTextColor.WHITE))
                    .build());
            LinkMigrationSnapshot linkStats = snapshot.links().getOrDefault(neighbor.id, new LinkMigrationSnapshot(0L, 0L));
            long net = linkStats.net();
            String netDisplay = net > 0 ? "+" + net : Long.toString(net);
            player.sendMessage(Component.text()
                    .append(Component.text("    Arrivals: ", NamedTextColor.GREEN))
                    .append(Component.text(Long.toString(linkStats.arrivals()), NamedTextColor.WHITE))
                    .append(Component.text("  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Departures: ", NamedTextColor.RED))
                    .append(Component.text(Long.toString(linkStats.departures()), NamedTextColor.WHITE))
                    .append(Component.text("  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Net: ", NamedTextColor.GOLD))
                    .append(Component.text(netDisplay, NamedTextColor.WHITE))
                    .build());
        }
        if (display.size() < links.size()) {
            int remaining = links.size() - display.size();
            CommandFeedback.sendInfo(player, remaining + " more link" + (remaining == 1 ? "" : "s") + " not shown.");
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return cityManager.all().stream().map(c -> c.id).collect(Collectors.toList());
        }
        return List.of();
    }
}
