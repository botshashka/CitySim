package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.economy.DistrictKey;
import dev.citysim.economy.DistrictStats;
import dev.citysim.economy.EconomyOverlayRenderer;
import dev.citysim.economy.EconomyService;
import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DistrictCommand implements CitySubcommand {

    private final CityManager cityManager;
    private final EconomyService economyService;
    private final EconomyOverlayRenderer overlayRenderer;

    public DistrictCommand(CityManager cityManager,
                           EconomyService economyService,
                           EconomyOverlayRenderer overlayRenderer) {
        this.cityManager = cityManager;
        this.economyService = economyService;
        this.overlayRenderer = overlayRenderer;
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
        return "citysim.command.district";
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
        if (!economyService.settings().enabled()) {
            player.sendMessage(Component.text("The economy module is disabled in config.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /city district <list|show|hide>", NamedTextColor.RED));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> handleList(player, args);
            case "show" -> handleShow(player, args);
            case "hide" -> handleHide(player);
            default -> player.sendMessage(Component.text("Unknown district subcommand.", NamedTextColor.RED));
        }
        return true;
    }

    private void handleList(Player player, String[] args) {
        City city = null;
        if (args.length >= 2) {
            city = cityManager.get(args[1]);
        }
        if (city == null) {
            city = cityManager.cityAt(player.getLocation());
        }
        if (city == null) {
            player.sendMessage(Component.text("Stand in a city or pass /city district list <cityId> [page]", NamedTextColor.RED));
            return;
        }
        int page = 1;
        if (args.length >= 3) {
            try {
                page = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException ignored) {
            }
        }
        Map<DistrictKey, DistrictStats> districts = economyService.grid(city.id);
        if (districts.isEmpty()) {
            player.sendMessage(Component.text("No district data yet.", NamedTextColor.YELLOW));
            return;
        }
        List<Map.Entry<DistrictKey, DistrictStats>> sorted = new ArrayList<>(districts.entrySet());
        sorted.sort(Comparator.comparingDouble(e -> -e.getValue().landValueRaw()));
        int perPage = 7;
        int totalPages = (int) Math.ceil(sorted.size() / (double) perPage);
        if (page > totalPages) {
            page = totalPages;
        }
        if (page <= 0) {
            page = 1;
        }
        int start = (page - 1) * perPage;
        int end = Math.min(sorted.size(), start + perPage);
        StringBuilder lines = new StringBuilder();
        for (int index = start; index < end; index++) {
            Map.Entry<DistrictKey, DistrictStats> entry = sorted.get(index);
            DistrictKey key = entry.getKey();
            DistrictStats stats = entry.getValue();
            if (index > start) {
                lines.append('\n');
            }
            lines.append("<gray>#").append(index + 1).append("</gray> ")
                    .append("<white>(")
                    .append(key.chunkX()).append(',').append(key.chunkZ()).append(")</white> ")
                    .append("<gold>LVI</gold> ")
                    .append(String.format(Locale.US, "%.1f", stats.landValueRaw()))
                    .append(" <green>nature</green> ")
                    .append(String.format(Locale.US, "%.2f", stats.nature()))
                    .append(" <red>pollution</red> ")
                    .append(String.format(Locale.US, "%.2f", stats.pollution()))
                    .append(" <aqua>access</aqua> ")
                    .append(String.format(Locale.US, "%.2f", stats.access()));
        }
        String safeName = AdventureMessages.escapeMiniMessage(city.name);
        String header = "<white><b>%s — Districts (page %d/%d)</b></white>".formatted(safeName, page, Math.max(1, totalPages));
        player.sendMessage(AdventureMessages.mini(header + "\n" + lines));
    }

    private void handleShow(Player player, String[] args) {
        if (args.length < 2 || !"landvalue".equalsIgnoreCase(args[1])) {
            player.sendMessage(Component.text("Usage: /city district show landvalue [cityId]", NamedTextColor.RED));
            return;
        }
        City city = null;
        if (args.length >= 3) {
            city = cityManager.get(args[2]);
        }
        if (city == null) {
            city = cityManager.cityAt(player.getLocation());
        }
        if (city == null) {
            player.sendMessage(Component.text("Stand in a city or pass /city district show landvalue <cityId>", NamedTextColor.RED));
            return;
        }
        if (economyService.grid(city.id).isEmpty()) {
            player.sendMessage(Component.text("No district data yet.", NamedTextColor.YELLOW));
            return;
        }
        overlayRenderer.showLandValue(player, city);
        player.sendMessage(Component.text("District overlay enabled for %d seconds.".formatted(settingsSeconds()), NamedTextColor.GREEN));
    }

    private void handleHide(Player player) {
        overlayRenderer.hide(player);
        player.sendMessage(Component.text("District overlay cleared.", NamedTextColor.GREEN));
    }

    private long settingsSeconds() {
        return economyService.settings().overlayTtlSeconds();
    }
}
