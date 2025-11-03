package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.economy.DistrictStats;
import dev.citysim.economy.EconomyOverlayRenderer;
import dev.citysim.economy.EconomyService;
import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class DistrictCommand implements CitySubcommand {
    private static final int PAGE_SIZE = 8;

    private final CityManager cityManager;
    private final EconomyService economyService;

    public DistrictCommand(CityManager cityManager, EconomyService economyService) {
        this.cityManager = cityManager;
        this.economyService = economyService;
    }

    @Override
    public String name() {
        return "district";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public String permission() {
        return "citysim.district";
    }

    @Override
    public List<Component> help() {
        return List.of(
                CommandMessages.help("/city district list [cityId] [page]"),
                CommandMessages.help("/city district show landvalue [cityId]"),
                CommandMessages.help("/city district hide")
        );
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (economyService == null || !economyService.isEnabled()) {
            player.sendMessage(Component.text("Economy module disabled in configuration.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /city district <list|show|hide> ...", NamedTextColor.RED));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "list" -> handleList(player, slice(args, 1));
            case "show" -> handleShow(player, slice(args, 1));
            case "hide" -> handleHide(player);
            default -> {
                player.sendMessage(Component.text("Unknown district subcommand.", NamedTextColor.RED));
                yield true;
            }
        };
    }

    private boolean handleList(Player player, String[] args) {
        City city = resolveCity(player, args);
        if (city == null) {
            player.sendMessage(Component.text("Stand in a city or pass /city district list <cityId> [page].", NamedTextColor.RED));
            return true;
        }
        int page = parsePage(args);
        List<DistrictStats> ordered = economyService.sortedDistricts(city.id);
        if (ordered.isEmpty()) {
            player.sendMessage(Component.text("No districts are tracked yet for this city.", NamedTextColor.YELLOW));
            return true;
        }
        int totalPages = Math.max(1, (int) Math.ceil(ordered.size() / (double) PAGE_SIZE));
        int pageIndex = Math.min(Math.max(1, page), totalPages) - 1;
        int start = pageIndex * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, ordered.size());
        List<DistrictStats> slice = ordered.subList(start, end);

        String safeName = AdventureMessages.escapeMiniMessage(city.name);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < slice.size(); i++) {
            DistrictStats stats = slice.get(i);
            int rank = start + i + 1;
            body.append(rank).append(". <white>")
                    .append(stats.key().chunkX()).append(',').append(stats.key().chunkZ())
                    .append("</white> LVI ")
                    .append(String.format(Locale.US, "%.1f", stats.landValue0to100()))
                    .append("  <green>nature </green>")
                    .append(String.format(Locale.US, "%.2f", stats.natureRatio()))
                    .append("  <red>pollution </red>")
                    .append(String.format(Locale.US, "%.2f", stats.pollutionRatio()))
                    .append("  <aqua>access </aqua>")
                    .append(String.format(Locale.US, "%.2f", stats.accessScore()))
                    .append('\n');
        }

        String message = ("""
        <white><b>%s — Districts</b></white>
        Page %d/%d
        %s
        """).formatted(
                safeName,
                pageIndex + 1,
                totalPages,
                body.toString().stripTrailing()
        ).stripTrailing();
        player.sendMessage(AdventureMessages.mini(message));
        return true;
    }

    private boolean handleShow(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /city district show landvalue [cityId]", NamedTextColor.RED));
            return true;
        }
        String modeArg = args[0].toLowerCase(Locale.ROOT);
        if (!modeArg.equals("landvalue")) {
            player.sendMessage(Component.text("Only landvalue overlay is available.", NamedTextColor.RED));
            return true;
        }
        City city = resolveCity(player, slice(args, 1));
        if (city == null) {
            player.sendMessage(Component.text("Stand in a city or pass /city district show landvalue <cityId>.", NamedTextColor.RED));
            return true;
        }
        economyService.showOverlay(player, city, EconomyOverlayRenderer.OverlayMode.LAND_VALUE);
        player.sendMessage(Component.text("Land value overlay enabled for %s.".formatted(city.name), NamedTextColor.GREEN));
        return true;
    }

    private boolean handleHide(Player player) {
        economyService.hideOverlay(player);
        player.sendMessage(Component.text("District overlay cleared.", NamedTextColor.GREEN));
        return true;
    }

    private City resolveCity(Player player, String[] args) {
        City city = null;
        if (args.length >= 1 && !args[0].isEmpty()) {
            try {
                Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
                city = cityManager.get(args[0]);
                if (city != null) {
                    return city;
                }
            }
        }
        return city != null ? city : cityManager.cityAt(player.getLocation());
    }

    private int parsePage(String[] args) {
        if (args.length == 0) {
            return 1;
        }
        if (args.length == 1) {
            try {
                return Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        try {
            return Integer.parseInt(args[1]);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static String[] slice(String[] args, int from) {
        if (from <= 0) {
            return args;
        }
        if (from >= args.length) {
            return new String[0];
        }
        String[] result = new String[args.length - from];
        System.arraycopy(args, from, result, 0, result.length);
        return result;
    }
}
