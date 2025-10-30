package dev.citysim.integration.traincarts;

import dev.citysim.city.City;
import dev.citysim.city.Cuboid;
import dev.citysim.stats.StationCountResult;
import dev.citysim.stats.StationCounter;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

public class TrainCartsStationService implements StationCounter {

    private final Plugin plugin;
    private final Server server;
    private final TrainCartsReflectionBinder.TrainCartsBinding binding;
    private final StationSignParser parser;

    private boolean failureLogged;

    public TrainCartsStationService(Plugin plugin,
                                    Server server,
                                    Plugin trainCarts) throws ReflectiveOperationException {
        this(plugin, server, trainCarts, new TrainCartsReflectionBinder(plugin.getLogger()), new StationSignParser());
    }

    public TrainCartsStationService(Plugin plugin,
                                    Server server,
                                    Plugin trainCarts,
                                    TrainCartsReflectionBinder binder,
                                    StationSignParser parser) throws ReflectiveOperationException {
        this(plugin, server, trainCarts, binder.bind(trainCarts), parser);
    }

    public TrainCartsStationService(Plugin plugin,
                                    Server server,
                                    Plugin trainCarts,
                                    TrainCartsReflectionBinder.TrainCartsBinding binding,
                                    StationSignParser parser) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = Objects.requireNonNull(server, "server");
        Objects.requireNonNull(trainCarts, "trainCarts");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    @Override
    public Optional<StationCountResult> countStations(City city) {
        if (city == null || city.cuboids == null || city.cuboids.isEmpty()) {
            return Optional.of(new StationCountResult(0, 0));
        }

        Map<World, List<Cuboid>> cuboidsByWorld = new HashMap<>();
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null || cuboid.world == null) {
                continue;
            }
            World world = server.getWorld(cuboid.world);
            if (world == null) {
                continue;
            }
            cuboidsByWorld.computeIfAbsent(world, w -> new ArrayList<>()).add(cuboid);
        }

        if (cuboidsByWorld.isEmpty()) {
            return Optional.of(new StationCountResult(0, 0));
        }

        Object signController;
        try {
            signController = binding.getSignController();
        } catch (ReflectiveOperationException ex) {
            return logFailure("accessing TrainCarts sign controller", ex);
        }
        if (signController == null) {
            return Optional.empty();
        }

        Set<String> counted = new HashSet<>();
        int total = 0;

        try {
            for (Map.Entry<World, List<Cuboid>> entry : cuboidsByWorld.entrySet()) {
                World world = entry.getKey();
                Object worldController = binding.getWorldController(signController, world);
                if (worldController == null || !binding.isWorldEnabled(worldController)) {
                    continue;
                }

                Collection<?> chunks = binding.loadSignChunks(worldController);
                if (chunks == null || chunks.isEmpty()) {
                    continue;
                }

                for (Object chunk : chunks) {
                    if (chunk == null) {
                        continue;
                    }
                    Object[] entries = binding.resolveEntries(chunk);
                    if (entries == null || entries.length == 0) {
                        continue;
                    }
                    for (Object rawEntry : entries) {
                        if (rawEntry == null) {
                            continue;
                        }
                        if (!binding.hasSignActionEvents(rawEntry)) {
                            continue;
                        }
                        Block block = binding.getBlock(rawEntry);
                        if (block == null || block.getWorld() != world) {
                            continue;
                        }
                        if (!isInsideAny(entry.getValue(), block.getX(), block.getY(), block.getZ())) {
                            continue;
                        }
                        List<TrainCartsReflectionBinder.StationText> texts = binding.resolveStationTexts(rawEntry, block);
                        if (!parser.isStationEntry(texts)) {
                            continue;
                        }
                        String key = blockKey(world, block.getX(), block.getY(), block.getZ());
                        if (counted.add(key)) {
                            total++;
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException ex) {
            return logFailure("counting TrainCarts station signs", ex);
        }

        failureLogged = false;
        int stations = (total >>> 1) + (total & 1);
        return Optional.of(new StationCountResult(stations, total));
    }

    private Optional<StationCountResult> logFailure(String context, Exception ex) {
        if (!failureLogged) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed while " + context + " for TrainCarts integration: " + ex.getMessage(), ex);
            failureLogged = true;
        }
        return Optional.empty();
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
