package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import dev.citysim.cmd.CommandFeedback;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.visual.SelectionTracker;
import dev.citysim.visual.SelectionTracker.SelectionSnapshot;
import dev.citysim.visual.SelectionTracker.YMode;
import dev.citysim.visual.VisualizationService;
import dev.citysim.stats.StationCountingMode;
import dev.citysim.stats.StatsService;
import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class EditCityCommand implements CitySubcommand {

    private static final Map<UUID, ConcurrentHashMap.KeySetView<String, Boolean>> ACTIVE_CITY_VIEWS = new ConcurrentHashMap<>();
    private static final Component MISSING_WORLD_WARNING = Component.text()
            .append(Component.text("Some cuboids could not be shown because their worlds are not loaded.", NamedTextColor.YELLOW))
            .build();

    private static final List<Component> HELP = List.of(
            CommandMessages.help("/city edit <cityId> name <new name>"),
            CommandMessages.help("/city edit <cityId> cuboid add"),
            CommandMessages.help("/city edit <cityId> cuboid remove"),
            CommandMessages.help("/city edit <cityId> cuboid show [on|off]"),
            CommandMessages.help("/city edit <cityId> cuboid list"),
            CommandMessages.help("/city edit <cityId> highrise <true|false>"),
            CommandMessages.help("/city edit <cityId> station <add|remove|set|clear> [amount]")
    );

    private final CityManager cityManager;
    private final StatsService statsService;
    private final SelectionTracker selectionTracker;
    private final VisualizationService visualizationService;

    public EditCityCommand(CityManager cityManager,
                           StatsService statsService,
                           SelectionTracker selectionTracker,
                           VisualizationService visualizationService) {
        this.cityManager = cityManager;
        this.statsService = statsService;
        this.selectionTracker = selectionTracker;
        this.visualizationService = visualizationService;
    }

    @Override
    public String name() {
        return "edit";
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
        return "/city edit <cityId> <action>";
    }

    @Override
    public List<Component> help() {
        return HELP;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendEditUsage(sender);
            return true;
        }

        String cityId = args[0];
        String action = args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "name" -> handleRename(sender, cityId, args);
            case "cuboid" -> handleCuboid(sender, cityId, args);
            case "addcuboid" -> handleLegacyCuboid(sender, cityId, "add", args);
            case "removecuboid" -> handleLegacyCuboid(sender, cityId, "remove", args);
            case "showcuboids" -> handleLegacyCuboid(sender, cityId, "show", args);
            case "listcuboids" -> handleLegacyCuboid(sender, cityId, "list", args);
            case "highrise" -> handleHighrise(sender, cityId, args);
            case "station" -> handleStation(sender, cityId, args);
            default -> {
                CommandFeedback.sendError(sender, "Unknown edit action. Use name, cuboid, highrise, or station.");
                yield true;
            }
        };
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return cityManager.all().stream().map(c -> c.id).collect(Collectors.toList());
        }
        if (args.length == 2) {
            return List.of("name", "cuboid", "highrise", "station");
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if ("name".equals(action)) {
            if (args.length == 3) {
                return List.of("<new name>");
            }
            return List.of();
        }
        if ("cuboid".equals(action)) {
            if (args.length == 3) {
                return List.of("add", "remove", "list", "show");
            }
            if (args.length == 4) {
                String sub = args[2].toLowerCase(Locale.ROOT);
                if ("show".equals(sub)) {
                    return List.of("on", "off");
                }
            }
            return List.of();
        }
        if ("highrise".equals(action) && args.length == 3) {
            return List.of("true", "false");
        }
        if ("station".equals(action)) {
            if (args.length == 3) {
                return List.of("add", "remove", "set", "clear");
            }
            if (args.length == 4) {
                String sub = args[2].toLowerCase(Locale.ROOT);
                if (List.of("add", "remove", "set").contains(sub)) {
                    return List.of("<amount>");
                }
            }
        }
        return List.of();
    }

    private boolean handleCuboid(CommandSender sender, String cityId, String[] args) {
        if (args.length < 3) {
            sendEditUsage(sender);
            return true;
        }

        String subAction = args[2].toLowerCase(Locale.ROOT);
        return switch (subAction) {
            case "add" -> handleCuboidAdd(sender, cityId);
            case "remove" -> handleRemoveCuboid(sender, cityId);
            case "list" -> handleListCuboids(sender, cityId);
            case "show" -> handleShowCuboids(sender, cityId, args.length >= 4 ? args[3] : null);
            default -> {
                CommandFeedback.sendError(sender, "Unknown cuboid action. Use add, remove, list, or show.");
                yield true;
            }
        };
    }

    private boolean handleLegacyCuboid(CommandSender sender, String cityId, String legacyAction, String[] args) {
        String replacement = switch (legacyAction) {
            case "add" -> "add";
            case "remove" -> "remove";
            case "list" -> "list";
            case "show" -> "show";
            default -> legacyAction;
        };
        CommandFeedback.sendWarning(sender, "This command is deprecated. Use /city edit " + cityId + " cuboid " + replacement + " instead.");

        return switch (legacyAction) {
            case "add" -> handleCuboidAdd(sender, cityId);
            case "remove" -> handleRemoveCuboid(sender, cityId);
            case "list" -> handleListCuboids(sender, cityId);
            case "show" -> handleShowCuboids(sender, cityId, args.length >= 3 ? args[2] : null);
            default -> true;
        };
    }

    private boolean handleRename(CommandSender sender, String cityId, String[] args) {
        if (args.length < 3) {
            sendEditUsage(sender);
            return true;
        }

        String newName = joinArgs(Arrays.copyOfRange(args, 2, args.length));
        if (newName.isEmpty()) {
            CommandFeedback.sendError(sender, "New name cannot be empty.");
            return true;
        }

        try {
            City renamed = cityManager.rename(cityId, newName);
            cityManager.save();
            statsService.updateCity(renamed, true);
            sender.sendMessage(Component.text()
                    .append(Component.text("City renamed to ", NamedTextColor.GREEN))
                    .append(Component.text(renamed.name, NamedTextColor.GREEN))
                    .append(Component.text(" (ID: " + renamed.id + ").", NamedTextColor.GREEN))
                    .build());
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    public boolean handleCuboidAdd(CommandSender sender, String cityId) {
        if (!(sender instanceof Player player)) {
            CommandFeedback.sendPlayersOnly(sender);
            return true;
        }

        SelectionSnapshot snapshot = selectionTracker.snapshot(player).orElse(null);
        if (snapshot == null || !snapshot.ready()) {
            player.sendMessage(Component.text("You must select two corners with the CitySim wand first!", NamedTextColor.RED));
            return true;
        }
        Location pos1 = snapshot.pos1Location();
        Location pos2 = snapshot.pos2Location();
        World selectionWorld = snapshot.world();
        if (selectionWorld == null || pos1 == null || pos2 == null) {
            player.sendMessage(Component.text("Your selection is incomplete. Use /city wand clear and try again.", NamedTextColor.RED));
            return true;
        }
        if (pos1.getWorld() != pos2.getWorld()) {
            player.sendMessage(Component.text("Your selection must be in a single world.", NamedTextColor.RED));
            return true;
        }
        if (selectionWorld != player.getWorld()) {
            player.sendMessage(Component.text("You are in a different world than your selection.", NamedTextColor.RED));
            return true;
        }

        City city = cityManager.get(cityId);
        if (city == null) {
            player.sendMessage(Component.text()
                    .append(Component.text("City with id '", NamedTextColor.RED))
                    .append(Component.text(cityId, NamedTextColor.RED))
                    .append(Component.text("' does not exist.", NamedTextColor.RED))
                    .build());
            return true;
        }

        boolean fullHeight = snapshot.mode() == YMode.FULL;
        Cuboid cuboid = new Cuboid(selectionWorld, pos1, pos2, fullHeight);

        try {
            int index = cityManager.addCuboid(city.id, cuboid);
            cityManager.save();
            statsService.updateCity(city, true);

            int width = cuboid.maxX - cuboid.minX + 1;
            int length = cuboid.maxZ - cuboid.minZ + 1;
            int height = cuboid.maxY - cuboid.minY + 1;
            String mode = fullHeight ? "full" : "span";
            Component message = Component.text()
                    .append(Component.text("Added cuboid #", NamedTextColor.GREEN))
                    .append(Component.text(Integer.toString(index), NamedTextColor.GREEN))
                    .append(Component.text(" to ", NamedTextColor.GREEN))
                    .append(Component.text(city.name, NamedTextColor.GREEN))
                    .append(Component.text(" (" + width + "×" + length + "×" + height + ", mode: " + mode + ").", NamedTextColor.GREEN))
                    .build();
            player.sendMessage(message);
            refreshShowCuboids(city);
            selectionTracker.clear(player);
        } catch (IllegalArgumentException ex) {
            player.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    private boolean handleRemoveCuboid(CommandSender sender, String cityId) {
        if (!(sender instanceof Player player)) {
            CommandFeedback.sendPlayersOnly(sender);
            return true;
        }

        City city = cityManager.get(cityId);
        if (city == null) {
            player.sendMessage(Component.text()
                    .append(Component.text("City with id '", NamedTextColor.RED))
                    .append(Component.text(cityId, NamedTextColor.RED))
                    .append(Component.text("' does not exist.", NamedTextColor.RED))
                    .build());
            return true;
        }

        int removed;
        try {
            removed = cityManager.removeCuboidsContaining(city.id, player.getLocation());
        } catch (IllegalArgumentException ex) {
            player.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
            return true;
        }

        if (removed == 0) {
            player.sendMessage(Component.text()
                    .append(Component.text("You are not standing inside any cuboids for ", NamedTextColor.WHITE))
                    .append(Component.text(city.name, NamedTextColor.WHITE))
                    .append(Component.text(".", NamedTextColor.WHITE))
                    .build());
            return true;
        }

        cityManager.save();
        statsService.updateCity(city, true);
        String suffix = removed == 1 ? " cuboid" : " cuboids";
        player.sendMessage(Component.text()
                .append(Component.text("Removed ", NamedTextColor.GREEN))
                .append(Component.text(Integer.toString(removed), NamedTextColor.GREEN))
                .append(Component.text(suffix, NamedTextColor.GREEN))
                .append(Component.text(" from ", NamedTextColor.GREEN))
                .append(Component.text(city.name, NamedTextColor.GREEN))
                .append(Component.text(".", NamedTextColor.GREEN))
                .build());
        refreshShowCuboids(city);
        return true;
    }

    private boolean handleShowCuboids(CommandSender sender, String cityId, String explicitToggle) {
        if (!(sender instanceof Player player)) {
            CommandFeedback.sendPlayersOnly(sender);
            return true;
        }

        City city = cityManager.get(cityId);
        if (city == null) {
            player.sendMessage(Component.text()
                    .append(Component.text("City with id '", NamedTextColor.RED))
                    .append(Component.text(cityId, NamedTextColor.RED))
                    .append(Component.text("' does not exist.", NamedTextColor.RED))
                    .build());
            return true;
        }

        ConcurrentHashMap.KeySetView<String, Boolean> active = ACTIVE_CITY_VIEWS.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet());
        boolean currentlyEnabled = active.contains(cityId);

        Boolean explicitToggleValue = null;
        if (explicitToggle != null) {
            explicitToggleValue = parseBoolean(explicitToggle);
            if (explicitToggleValue == null) {
                CommandFeedback.sendError(sender, "Showcuboids value must be on/off.");
                return true;
            }
        }

        boolean enable = explicitToggleValue != null ? explicitToggleValue : !currentlyEnabled;

        if (!enable) {
            if (!currentlyEnabled) {
                player.sendMessage(Component.text()
                        .append(Component.text("Cuboid previews are already disabled.", NamedTextColor.YELLOW))
                        .build());
                return true;
            }
            active.remove(cityId);
            if (active.isEmpty()) {
                ACTIVE_CITY_VIEWS.remove(player.getUniqueId());
            }
            visualizationService.disableCityView(player, cityId);
            player.sendMessage(Component.text()
                    .append(Component.text("Hid cuboids for ", NamedTextColor.GREEN))
                    .append(Component.text(city.name, NamedTextColor.GREEN))
                    .build());
            return true;
        }

        if (city.cuboids == null || city.cuboids.isEmpty()) {
            player.sendMessage(Component.text()
                    .append(Component.text("City '", NamedTextColor.RED))
                    .append(Component.text(city.name, NamedTextColor.RED))
                    .append(Component.text("' has no cuboids to preview.", NamedTextColor.RED))
                    .build());
            return true;
        }

        CuboidPreviewSources sources = buildCuboidPreviewSources(city);
        if (sources.cuboidsByWorld().isEmpty()) {
            Component message;
            if (sources.missingWorld()) {
                message = Component.text()
                        .append(Component.text("Unable to preview cuboids because their worlds are not loaded.", NamedTextColor.RED))
                        .build();
            } else {
                message = Component.text()
                        .append(Component.text("City '", NamedTextColor.RED))
                        .append(Component.text(city.name, NamedTextColor.RED))
                        .append(Component.text("' has no loaded cuboids to preview.", NamedTextColor.RED))
                        .build();
            }
            player.sendMessage(message);
            return true;
        }

        active.add(cityId);
        visualizationService.enableCityView(player, cityId);
        visualizationService.updateCityView(List.of(player), cityId);
        if (sources.missingWorld()) {
            player.sendMessage(MISSING_WORLD_WARNING);
        }

        player.sendMessage(Component.text()
                .append(Component.text("Showing cuboids for ", NamedTextColor.GREEN))
                .append(Component.text(city.name, NamedTextColor.GREEN))
                .append(Component.text(". Use ", NamedTextColor.GREEN))
                .append(Component.text("/city edit " + city.id + " cuboid show off", NamedTextColor.AQUA))
                .append(Component.text(" to hide them.", NamedTextColor.GREEN))
                .build());

        return true;
    }

    private CuboidPreviewSources buildCuboidPreviewSources(City city) {
        Map<World, List<Cuboid>> cuboidsByWorld = new HashMap<>();
        boolean missingWorld = false;
        if (city == null || city.cuboids == null) {
            return new CuboidPreviewSources(cuboidsByWorld, false);
        }
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null || cuboid.world == null) {
                continue;
            }
            World world = Bukkit.getWorld(cuboid.world);
            if (world == null) {
                missingWorld = true;
                continue;
            }
            cuboidsByWorld.computeIfAbsent(world, ignored -> new ArrayList<>()).add(cuboid);
        }
        return new CuboidPreviewSources(cuboidsByWorld, missingWorld);
    }

    private void refreshShowCuboids(City city) {
        if (city == null) {
            return;
        }
        CuboidPreviewSources sources = buildCuboidPreviewSources(city);
        List<Player> viewers = new ArrayList<>();
        for (Map.Entry<UUID, ConcurrentHashMap.KeySetView<String, Boolean>> entry : ACTIVE_CITY_VIEWS.entrySet()) {
            if (!entry.getValue().contains(city.id)) {
                continue;
            }
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null && viewer.isOnline()) {
                viewers.add(viewer);
            }
        }

        if (viewers.isEmpty()) {
            return;
        }

        if (sources.cuboidsByWorld().isEmpty()) {
            for (Player viewer : viewers) {
                visualizationService.disableCityView(viewer, city.id);
                ACTIVE_CITY_VIEWS.computeIfPresent(viewer.getUniqueId(), (id, set) -> {
                    set.remove(city.id);
                    return set.isEmpty() ? null : set;
                });
                viewer.sendMessage(Component.text()
                        .append(Component.text("City '", NamedTextColor.YELLOW))
                        .append(Component.text(city.name, NamedTextColor.YELLOW))
                        .append(Component.text("' has no cuboids to preview; hiding outline.", NamedTextColor.YELLOW))
                        .build());
            }
            return;
        }

        visualizationService.updateCityView(viewers, city.id);
        if (sources.missingWorld()) {
            for (Player viewer : viewers) {
                viewer.sendMessage(MISSING_WORLD_WARNING);
            }
        }
    }

    private boolean handleListCuboids(CommandSender sender, String cityId) {
        City city = cityManager.get(cityId);
        if (city == null) {
            sender.sendMessage(Component.text()
                    .append(Component.text("City with id '", NamedTextColor.RED))
                    .append(Component.text(cityId, NamedTextColor.RED))
                    .append(Component.text("' does not exist.", NamedTextColor.RED))
                    .build());
            return true;
        }

        List<Cuboid> cuboids = city.cuboids;
        if (cuboids == null || cuboids.isEmpty()) {
            sender.sendMessage(AdventureMessages.joinLines(
                    Component.text()
                            .append(Component.text("City '", NamedTextColor.YELLOW))
                            .append(Component.text(city.name, NamedTextColor.YELLOW))
                            .append(Component.text("' has no cuboids defined.", NamedTextColor.YELLOW))
                            .build()
            ));
            return true;
        }

        List<Component> lines = new ArrayList<>();
        lines.add(Component.text()
                .append(Component.text("Cuboids for ", NamedTextColor.GOLD))
                .append(Component.text(city.name, NamedTextColor.GOLD))
                .append(Component.text(" (", NamedTextColor.GOLD))
                .append(Component.text(city.id, NamedTextColor.GOLD))
                .append(Component.text("):", NamedTextColor.GOLD))
                .build());

        for (int i = 0; i < cuboids.size(); i++) {
            Cuboid cuboid = cuboids.get(i);
            if (cuboid == null) {
                lines.add(Component.text()
                        .append(Component.text("#" + (i + 1) + " ", NamedTextColor.GRAY))
                        .append(Component.text("<invalid cuboid>", NamedTextColor.RED))
                        .build());
                continue;
            }

            String worldName = cuboid.world != null ? cuboid.world : "<unknown>";
            int width = cuboid.maxX - cuboid.minX + 1;
            int length = cuboid.maxZ - cuboid.minZ + 1;
            int height = cuboid.maxY - cuboid.minY + 1;
            String mode = cuboid.fullHeight ? "full" : "span";
            String size = width + "×" + length + "×" + height;

            lines.add(Component.text()
                    .append(Component.text("#" + (i + 1) + " ", NamedTextColor.GRAY))
                    .append(Component.text(worldName + ": ", NamedTextColor.WHITE))
                    .append(Component.text(cuboid.minX + "," + cuboid.minY + "," + cuboid.minZ, NamedTextColor.WHITE))
                    .append(Component.text(" -> ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(cuboid.maxX + "," + cuboid.maxY + "," + cuboid.maxZ, NamedTextColor.WHITE))
                    .append(Component.text(" (", NamedTextColor.DARK_GRAY))
                    .append(Component.text(mode, NamedTextColor.AQUA))
                    .append(Component.text(", ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(size, NamedTextColor.AQUA))
                    .append(Component.text(")", NamedTextColor.DARK_GRAY))
                    .build());
        }

        sender.sendMessage(AdventureMessages.joinLines(lines.toArray(Component[]::new)));
        return true;
    }

    private static final class CuboidPreviewSources {
        private final Map<World, List<Cuboid>> cuboidsByWorld;
        private final boolean missingWorld;

        private CuboidPreviewSources(Map<World, List<Cuboid>> cuboidsByWorld, boolean missingWorld) {
            this.cuboidsByWorld = cuboidsByWorld;
            this.missingWorld = missingWorld;
        }

        private Map<World, List<Cuboid>> cuboidsByWorld() {
            return cuboidsByWorld;
        }

        private boolean missingWorld() {
            return missingWorld;
        }
    }

    private boolean handleHighrise(CommandSender sender, String cityId, String[] args) {
        if (args.length < 3) {
            sendEditUsage(sender);
            return true;
        }

        String value = args[2];
        Boolean enable = parseBoolean(value);
        if (enable == null) {
            CommandFeedback.sendError(sender, "Highrise value must be true/false.");
            return true;
        }

        City city = cityManager.get(cityId);
        if (city == null) {
            sender.sendMessage(Component.text()
                    .append(Component.text("City with id '", NamedTextColor.RED))
                    .append(Component.text(cityId, NamedTextColor.RED))
                    .append(Component.text("' does not exist.", NamedTextColor.RED))
                    .build());
            return true;
        }

        try {
            cityManager.setHighrise(city.id, enable);
            cityManager.save();
            statsService.updateCity(city, true);
            sender.sendMessage(Component.text()
                    .append(Component.text("City '", NamedTextColor.GREEN))
                    .append(Component.text(city.name, NamedTextColor.GREEN))
                    .append(Component.text("' highrise set to ", NamedTextColor.GREEN))
                    .append(Component.text(Boolean.toString(enable), NamedTextColor.GREEN))
                    .append(Component.text(".", NamedTextColor.GREEN))
                    .build());
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    private boolean handleStation(CommandSender sender, String cityId, String[] args) {
        if (args.length < 3) {
            sendStationUsage(sender);
            return true;
        }

        City city = cityManager.get(cityId);
        if (city == null) {
            sender.sendMessage(Component.text()
                    .append(Component.text("City with id '", NamedTextColor.RED))
                    .append(Component.text(cityId, NamedTextColor.RED))
                    .append(Component.text("' does not exist.", NamedTextColor.RED))
                    .build());
            return true;
        }

        StationCountingMode mode = statsService.getStationCountingMode();
        if (mode == StationCountingMode.TRAIN_CARTS) {
            CommandFeedback.sendError(sender, "Station counts are managed automatically by TrainCarts; manual edits are disabled.");
            return true;
        }
        if (mode == StationCountingMode.DISABLED) {
            CommandFeedback.sendError(sender, "Station tracking is disabled in the configuration.");
            return true;
        }

        String stationAction = args[2].toLowerCase(Locale.ROOT);
        int previousStations = Math.max(0, city.stations);

        Integer updated = switch (stationAction) {
            case "add" -> modifyStations(sender, args, previousStations, "stations to add", Integer::sum);
            case "remove" -> modifyStations(sender, args, previousStations, "stations to remove", (a, b) -> Math.max(0, a - b));
            case "set" -> modifyStations(sender, args, previousStations, "station count", (a, b) -> b);
            case "clear" -> 0;
            default -> {
                sendStationUsage(sender);
                yield null;
            }
        };

        if (updated == null) {
            return true;
        }

        if (updated < 0) {
            updated = 0;
        }

        city.stations = updated;
        cityManager.save();
        statsService.updateCity(city, true);

        if (updated == previousStations) {
            String word = updated == 1 ? " station" : " stations";
            sender.sendMessage(Component.text()
                    .append(Component.text("City '", NamedTextColor.WHITE))
                    .append(Component.text(city.name, NamedTextColor.WHITE))
                    .append(Component.text("' remains at ", NamedTextColor.WHITE))
                    .append(Component.text(Integer.toString(updated), NamedTextColor.WHITE))
                    .append(Component.text(word, NamedTextColor.WHITE))
                    .append(Component.text(".", NamedTextColor.WHITE))
                    .build());
        } else {
            String newWord = updated == 1 ? " station" : " stations";
            String oldWord = previousStations == 1 ? " station" : " stations";
            sender.sendMessage(Component.text()
                    .append(Component.text("City '", NamedTextColor.GREEN))
                    .append(Component.text(city.name, NamedTextColor.GREEN))
                    .append(Component.text("' now has ", NamedTextColor.GREEN))
                    .append(Component.text(Integer.toString(updated), NamedTextColor.GREEN))
                    .append(Component.text(newWord, NamedTextColor.GREEN))
                    .append(Component.text(" (was ", NamedTextColor.GREEN))
                    .append(Component.text(Integer.toString(previousStations), NamedTextColor.GREEN))
                    .append(Component.text(oldWord, NamedTextColor.GREEN))
                    .append(Component.text(").", NamedTextColor.GREEN))
                    .build());
        }
        return true;
    }

    private Integer modifyStations(CommandSender sender, String[] args, int base, String context, StationOperator operator) {
        if (args.length < 4) {
            sendStationUsage(sender);
            return null;
        }
        Integer amount = parseNonNegative(args[3], sender, context);
        if (amount == null) {
            return null;
        }
        return operator.apply(base, amount);
    }

    private Integer parseNonNegative(String value, CommandSender sender, String context) {
        int amount;
        try {
            amount = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Component.text()
                    .append(Component.text("Invalid number for ", NamedTextColor.RED))
                    .append(Component.text(context, NamedTextColor.RED))
                    .append(Component.text(": ", NamedTextColor.RED))
                    .append(Component.text(value, NamedTextColor.RED))
                    .build());
            return null;
        }
        if (amount < 0) {
            CommandFeedback.sendError(sender, "Amount must be zero or positive.");
            return null;
        }
        return amount;
    }

    private void sendStationUsage(CommandSender sender) {
        sender.sendMessage(AdventureMessages.joinLines(
                CommandMessages.usage("Usage:"),
                CommandMessages.usage("/city edit <cityId> station <add|remove|set|clear> [amount]")
        ));
    }

    private void sendEditUsage(CommandSender sender) {
        List<Component> lines = new ArrayList<>(HELP.size() + 1);
        lines.add(CommandMessages.usage("Usage:"));
        lines.addAll(HELP);
        sender.sendMessage(AdventureMessages.joinLines(lines.toArray(Component[]::new)));
    }

    private String joinArgs(String[] args) {
        return String.join(" ", args).trim();
    }

    private Boolean parseBoolean(String value) {
        if (value == null) {
            return null;
        }
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on")) {
            return true;
        }
        if (value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off")) {
            return false;
        }
        return null;
    }

    public static void stopShowCuboids(Player player) {
        if (player == null) {
            return;
        }
        ACTIVE_CITY_VIEWS.remove(player.getUniqueId());
    }

    @FunctionalInterface
    private interface StationOperator {
        int apply(int base, int amount);
    }

}
