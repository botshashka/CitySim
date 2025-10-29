package dev.citysim.integration.traincarts;

import dev.citysim.city.City;
import dev.citysim.city.Cuboid;
import dev.citysim.stats.StationCounter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.logging.Level;

public class TrainCartsStationService implements StationCounter {
    private final JavaPlugin plugin;
    private final Plugin trainCarts;
    private final Method getSignControllerMethod;
    private final Method signControllerForWorldMethod;
    private final Method signControllerWorldIsEnabledMethod;
    private final Field signChunksField;
    private final Method longHashMapValuesMethod;
    private final Method chunkGetEntriesMethod;
    private final Method entryGetBlockMethod;
    private final Method entryHasSignActionEventsMethod;
    private final Method entryCreateFrontTrackedSignMethod;
    private final Method entryCreateBackTrackedSignMethod;
    private final Object railPieceNone;
    private final Method trackedSignGetLineMethod;

    private boolean failureLogged;

    public TrainCartsStationService(JavaPlugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        Plugin found = findTrainCartsPlugin(plugin);
        if (found == null) {
            throw new IllegalStateException("TrainCarts plugin not found");
        }
        this.trainCarts = found;

        ClassLoader loader = found.getClass().getClassLoader();
        Class<?> trainCartsClass = found.getClass();
        this.getSignControllerMethod = trainCartsClass.getMethod("getSignController");

        Class<?> signControllerClass = Class.forName("com.bergerkiller.bukkit.tc.controller.global.SignController", true, loader);
        this.signControllerForWorldMethod = signControllerClass.getMethod("forWorld", World.class);

        Class<?> signControllerWorldClass = Class.forName("com.bergerkiller.bukkit.tc.controller.global.SignControllerWorld", true, loader);
        this.signControllerWorldIsEnabledMethod = signControllerWorldClass.getMethod("isEnabled");
        this.signChunksField = signControllerWorldClass.getDeclaredField("signChunks");
        this.signChunksField.setAccessible(true);

        Class<?> longHashMapClass = Class.forName("com.bergerkiller.bukkit.common.wrappers.LongHashMap", true, loader);
        this.longHashMapValuesMethod = longHashMapClass.getMethod("values");

        Class<?> signControllerChunkClass = Class.forName("com.bergerkiller.bukkit.tc.controller.global.SignControllerChunk", true, loader);
        this.chunkGetEntriesMethod = signControllerChunkClass.getMethod("getEntries");

        Class<?> entryClass = Class.forName("com.bergerkiller.bukkit.tc.controller.global.SignController$Entry", true, loader);
        this.entryGetBlockMethod = entryClass.getMethod("getBlock");
        this.entryHasSignActionEventsMethod = entryClass.getMethod("hasSignActionEvents");
        Class<?> railPieceClass = Class.forName("com.bergerkiller.bukkit.tc.controller.components.RailPiece", true, loader);
        this.entryCreateFrontTrackedSignMethod = entryClass.getMethod("createFrontTrackedSign", railPieceClass);
        this.entryCreateBackTrackedSignMethod = entryClass.getMethod("createBackTrackedSign", railPieceClass);

        Field noneField = railPieceClass.getField("NONE");
        this.railPieceNone = noneField.get(null);

        Class<?> trackedSignClass = Class.forName("com.bergerkiller.bukkit.tc.rails.RailLookup$TrackedSign", true, loader);
        this.trackedSignGetLineMethod = trackedSignClass.getMethod("getLine", int.class);
    }

    private Plugin findTrainCartsPlugin(JavaPlugin plugin) {
        var pluginManager = plugin.getServer().getPluginManager();
        var direct = pluginManager.getPlugin("TrainCarts");
        if (isTrainCartsPlugin(direct)) {
            return direct;
        }
        var underscored = pluginManager.getPlugin("Train_Carts");
        if (isTrainCartsPlugin(underscored)) {
            return underscored;
        }
        for (var candidate : pluginManager.getPlugins()) {
            if (isTrainCartsPlugin(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isTrainCartsPlugin(Plugin plugin) {
        if (plugin == null) {
            return false;
        }
        return isTrainCartsName(plugin.getName());
    }

    private boolean isTrainCartsName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.replace("_", "").replace("-", "").toLowerCase();
        return "traincarts".equals(normalized);
    }

    @Override
    public OptionalInt countStations(City city) {
        if (city == null || city.cuboids == null || city.cuboids.isEmpty()) {
            return OptionalInt.of(0);
        }

        Map<World, List<Cuboid>> cuboidsByWorld = new HashMap<>();
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null || cuboid.world == null) {
                continue;
            }
            World world = Bukkit.getWorld(cuboid.world);
            if (world == null) {
                continue;
            }
            cuboidsByWorld.computeIfAbsent(world, w -> new ArrayList<>()).add(cuboid);
        }

        if (cuboidsByWorld.isEmpty()) {
            return OptionalInt.of(0);
        }

        Object signController;
        try {
            signController = getSignControllerMethod.invoke(trainCarts);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return logFailure("accessing TrainCarts sign controller", e);
        }
        if (signController == null) {
            return OptionalInt.empty();
        }

        Set<String> counted = new HashSet<>();
        int total = 0;

        try {
            for (Map.Entry<World, List<Cuboid>> entry : cuboidsByWorld.entrySet()) {
                World world = entry.getKey();
                Object worldController = signControllerForWorldMethod.invoke(signController, world);
                if (worldController == null) {
                    continue;
                }
                boolean enabled = Boolean.TRUE.equals(signControllerWorldIsEnabledMethod.invoke(worldController));
                if (!enabled) {
                    continue;
                }

                Object signChunks = signChunksField.get(worldController);
                if (signChunks == null) {
                    continue;
                }
                Collection<?> chunks;
                try {
                    chunks = (Collection<?>) longHashMapValuesMethod.invoke(signChunks);
                } catch (ClassCastException ex) {
                    return logFailure("reading TrainCarts sign chunk collection", ex);
                }
                if (chunks == null || chunks.isEmpty()) {
                    continue;
                }

                for (Object chunk : chunks) {
                    if (chunk == null) {
                        continue;
                    }
                    Object entriesObj = chunkGetEntriesMethod.invoke(chunk);
                    if (!(entriesObj instanceof Object[] entries)) {
                        continue;
                    }
                    for (Object rawEntry : entries) {
                        if (rawEntry == null) {
                            continue;
                        }
                        if (!Boolean.TRUE.equals(entryHasSignActionEventsMethod.invoke(rawEntry))) {
                            continue;
                        }
                        Block block = (Block) entryGetBlockMethod.invoke(rawEntry);
                        if (block == null || block.getWorld() != world) {
                            continue;
                        }
                        if (!isInsideAny(entry.getValue(), block.getX(), block.getY(), block.getZ())) {
                            continue;
                        }
                        if (!isStationEntry(rawEntry)) {
                            continue;
                        }
                        String key = blockKey(world, block.getX(), block.getY(), block.getZ());
                        if (counted.add(key)) {
                            total++;
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            return logFailure("counting TrainCarts station signs", e);
        }

        failureLogged = false;
        return OptionalInt.of(total);
    }

    private OptionalInt logFailure(String context, Exception ex) {
        if (!failureLogged) {
            plugin.getLogger().log(Level.WARNING, "Failed while " + context + " for TrainCarts integration: " + ex.getMessage(), ex);
            failureLogged = true;
        }
        return OptionalInt.empty();
    }

    private boolean isStationEntry(Object entry) throws ReflectiveOperationException {
        return isStationTrackedSign(entryCreateFrontTrackedSignMethod.invoke(entry, railPieceNone))
                || isStationTrackedSign(entryCreateBackTrackedSignMethod.invoke(entry, railPieceNone));
    }

    private boolean isStationTrackedSign(Object trackedSign) throws ReflectiveOperationException {
        if (trackedSign == null) {
            return false;
        }
        String header = readSignLine(trackedSign, 0);
        String action = readSignLine(trackedSign, 1);
        if (header == null || action == null) {
            return false;
        }
        if (!isTrainOrCartHeader(header)) {
            return false;
        }
        return isStationActionLine(action);
    }

    private String readSignLine(Object trackedSign, int index) throws ReflectiveOperationException {
        try {
            Object value = trackedSignGetLineMethod.invoke(trackedSign, index);
            if (value instanceof String str) {
                return str;
            }
            return value != null ? value.toString() : null;
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            throw new ReflectiveOperationException(cause != null ? cause : ex);
        }
    }

    private boolean isTrainOrCartHeader(String line) {
        String cleaned = stripFormatting(line);
        if (cleaned.isEmpty()) {
            return false;
        }
        if (cleaned.charAt(0) == '[' && cleaned.length() > 1) {
            cleaned = cleaned.substring(1);
        }
        if (!cleaned.isEmpty() && cleaned.charAt(cleaned.length() - 1) == ']') {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        cleaned = trimLeadingPunctuation(cleaned);
        if (cleaned.isEmpty()) {
            return false;
        }
        int colon = cleaned.indexOf(':');
        if (colon >= 0) {
            cleaned = cleaned.substring(0, colon);
        }
        cleaned = cleaned.replace(" ", "").replace("\t", "");
        if (cleaned.isEmpty()) {
            return false;
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        return lower.equals("train") || lower.equals("cart");
    }

    private boolean isStationActionLine(String line) {
        String cleaned = trimLeadingPunctuation(stripFormatting(line));
        if (cleaned.isEmpty()) {
            return false;
        }
        int space = cleaned.indexOf(' ');
        if (space >= 0) {
            cleaned = cleaned.substring(0, space);
        }
        int colon = cleaned.indexOf(':');
        if (colon >= 0) {
            cleaned = cleaned.substring(0, colon);
        }
        return cleaned.equalsIgnoreCase("station");
    }

    private String stripFormatting(String line) {
        if (line == null) {
            return "";
        }
        String stripped = ChatColor.stripColor(line);
        if (stripped == null) {
            stripped = line;
        }
        return stripped.trim();
    }

    private String trimLeadingPunctuation(String input) {
        int index = 0;
        while (index < input.length() && !Character.isLetterOrDigit(input.charAt(index))) {
            index++;
        }
        return index >= input.length() ? "" : input.substring(index);
    }

    private boolean isInsideAny(List<Cuboid> cuboids, int x, int y, int z) {
        for (Cuboid cuboid : cuboids) {
            if (cuboid == null) {
                continue;
            }
            if (x < cuboid.minX || x > cuboid.maxX) {
                continue;
            }
            if (z < cuboid.minZ || z > cuboid.maxZ) {
                continue;
            }
            if (y < cuboid.minY || y > cuboid.maxY) {
                continue;
            }
            return true;
        }
        return false;
    }

    private String blockKey(World world, int x, int y, int z) {
        return world.getUID() + ":" + x + ':' + y + ':' + z;
    }
}
