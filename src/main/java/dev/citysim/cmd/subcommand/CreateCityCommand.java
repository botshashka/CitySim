package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import dev.citysim.cmd.CommandFeedback;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.selection.SelectionListener;
import dev.citysim.selection.SelectionState;
import dev.citysim.stats.StatsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

public class CreateCityCommand implements CitySubcommand {

    private final CityManager cityManager;
    private final StatsService statsService;

    public CreateCityCommand(CityManager cityManager, StatsService statsService) {
        this.cityManager = cityManager;
        this.statsService = statsService;
    }

    @Override
    public String name() {
        return "create";
    }

    @Override
    public Collection<String> aliases() {
        return List.of("add");
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
        return "/city create <name>";
    }

    @Override
    public List<Component> help() {
        return List.of(
                CommandMessages.help("/city create <name>"),
                CommandMessages.help("/city add <name>")
        );
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            CommandFeedback.sendWarning(sender, "Usage: /city create <name>");
            return true;
        }

        String name = joinArgs(args);
        if (name.isEmpty()) {
            CommandFeedback.sendError(sender, "City name cannot be empty.");
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

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("<name>");
        }
        return List.of();
    }

    private String joinArgs(String[] args) {
        return String.join(" ", args).trim();
    }
}
