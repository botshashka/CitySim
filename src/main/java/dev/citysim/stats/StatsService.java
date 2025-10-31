
package dev.citysim.stats;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import dev.citysim.stats.schedule.ScanScheduler;
import dev.citysim.stats.scan.CityScanCallbacks;
import dev.citysim.stats.scan.CityScanRunner;
import dev.citysim.stats.scan.ScanContext;
import dev.citysim.stats.scan.ScanDebugManager;
import dev.citysim.stats.scan.ScanRequest;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

public class StatsService {

    private static final long DEFAULT_STATS_INITIAL_DELAY_TICKS = 40L;
    private static final long DEFAULT_STATS_INTERVAL_TICKS = 100L;
    private static final long MIN_STATS_INTERVAL_TICKS = 20L;
    private static final long MAX_STATS_INTERVAL_TICKS = 12000L; // 10 minutes at 20 TPS
    private static final long MAX_STATS_INITIAL_DELAY_TICKS = 6000L; // 5 minutes at 20 TPS

    private final Plugin plugin;
    private final CityManager cityManager;
    private volatile StationCounter stationCounter;
    private int taskId = -1;
    private long statsInitialDelayTicks = DEFAULT_STATS_INITIAL_DELAY_TICKS;
    private long statsIntervalTicks = DEFAULT_STATS_INTERVAL_TICKS;

    private long blockScanRefreshIntervalMillis = 60000L;

    private static final int HIGHRISE_VERTICAL_STEP = 4;

    private StationCountingMode stationCountingMode = StationCountingMode.MANUAL;
    private boolean stationCountingWarningLogged = false;

    private final HappinessCalculator happinessCalculator = new HappinessCalculator();
    private final ScanDebugManager scanDebugManager = new ScanDebugManager();
    private final CityScanCallbacks scanCallbacks = new StatsScanCallbacks();
    private final CityScanRunner scanRunner;
    private final ScanScheduler scanScheduler;

    public StatsService(Plugin plugin, CityManager cm, StationCounter stationCounter) {
        this.plugin = plugin;
        this.cityManager = cm;
        this.stationCounter = stationCounter;
        this.scanRunner = new CityScanRunner(scanCallbacks, scanDebugManager);
        this.scanScheduler = new ScanScheduler(cityManager, scanRunner);
        updateConfig();
    }

    public void setStationCounter(StationCounter stationCounter) {
        if (this.stationCounter == stationCounter) {
            return;
        }
        this.stationCounter = stationCounter;
        updateConfig();
    }

    public void start() {
        updateConfig();
        if (taskId != -1) return;
        scanScheduler.clear();
        scheduleInitialStartupScans();
        scheduleTask();
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        scanScheduler.clear();
    }

    public void restartTask() {
        updateConfig();
        stop();
        scanScheduler.clear();
        scheduleInitialStartupScans();
        scheduleTask();
    }

    public void requestCityUpdate(City city) {
        requestCityUpdate(city, false);
    }

    public void requestCityUpdate(City city, boolean forceRefresh) {
        requestCityUpdate(city, forceRefresh, null, null);
    }

    public void requestCityUpdate(City city, boolean forceRefresh, String reason) {
        requestCityUpdate(city, forceRefresh, reason, null);
    }

    public void requestCityUpdate(City city, boolean forceRefresh, String reason, Location triggerLocation) {
        if (city == null || city.id == null) {
            return;
        }
        addPendingCity(city.id, forceRefresh, reason, createContext(triggerLocation));
    }

    public void requestCityUpdate(Location location) {
        requestCityUpdate(location, false);
    }

    public void requestCityUpdate(Location location, boolean forceRefresh) {
        requestCityUpdate(location, forceRefresh, null);
    }

    public void requestCityUpdate(Location location, boolean forceRefresh, String reason) {
        if (location == null) {
            return;
        }
        City city = cityManager.cityAt(location);
        if (city != null) {
            requestCityUpdate(city, forceRefresh, reason, location);
        }
    }

    public boolean toggleScanDebug(Player player) {
        if (player == null) {
            return false;
        }
        return scanDebugManager.toggle(player);
    }

    public StationCountingMode getStationCountingMode() {
        return stationCountingMode;
    }

    private void addPendingCity(String cityId, boolean forceRefresh, String reason, ScanContext context) {
        addPendingCity(cityId, forceRefresh, false, reason, context);
    }

    private void addPendingCity(String cityId, boolean forceRefresh, boolean forceChunkLoad, String reason, ScanContext context) {
        scanScheduler.queueCity(cityId, forceRefresh, forceChunkLoad, reason, context);
    }

    private ScanContext createContext(Location location) {
        if (location == null) {
            return null;
        }
        String worldName = location.getWorld() != null ? location.getWorld().getName() : null;
        return new ScanContext(worldName, location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private void tick() {
        scanScheduler.tick();
    }

    private void scheduleInitialStartupScans() {
        Bukkit.getScheduler().runTask(plugin, this::runInitialStartupScans);
    }

    private void runInitialStartupScans() {
        scanRunner.clearActiveJobs();
        for (City city : cityManager.all()) {
            if (city == null || city.id == null || city.id.isEmpty()) {
                continue;
            }
            scanRunner.runSynchronously(city, new ScanRequest(true, true, "initial startup", null));
        }
    }

    public HappinessBreakdown updateCity(City city) {
        return updateCity(city, false);
    }

    public HappinessBreakdown updateCity(City city, boolean forceRefresh) {
        if (city == null) {
            return new HappinessBreakdown();
        }
        cancelActiveJob(city);
        HappinessBreakdown result = scanRunner.runSynchronously(city, new ScanRequest(forceRefresh, true, "synchronous update", null));
        return result != null ? result : new HappinessBreakdown();
    }

    private void cancelActiveJob(City city) {
        if (city == null || city.id == null) {
            return;
        }
        scanScheduler.cancel(city.id);
    }

    public HappinessBreakdown computeHappinessBreakdown(City city) {
        if (city == null) {
            return new HappinessBreakdown();
        }
        if (city.happinessBreakdown != null && city.blockScanCache != null) {
            return city.happinessBreakdown;
        }
        City.BlockScanCache metrics = city.blockScanCache;
        if (metrics != null) {
            HappinessBreakdown hb = calculateHappinessBreakdown(city, metrics);
            city.happinessBreakdown = hb;
            city.happiness = hb.total;
            return hb;
        }
        requestCityUpdate(city, true, "compute happiness breakdown");
        return city.happinessBreakdown != null ? city.happinessBreakdown : new HappinessBreakdown();
    }

    private StationCountResult refreshStationCount(City city) {
        if (city == null) {
            return null;
        }
        switch (stationCountingMode) {
            case DISABLED -> {
                return null;
            }
            case TRAIN_CARTS -> {
                StationCounter counter = stationCounter;
                if (counter == null) {
                    if (!stationCountingWarningLogged) {
                        plugin.getLogger().warning("TrainCarts station counting requested but integration is unavailable; using manual station totals.");
                        stationCountingWarningLogged = true;
                    }
                    return null;
                }
                try {
                    Optional<StationCountResult> counted = counter.countStations(city);
                    if (counted.isPresent()) {
                        StationCountResult result = counted.get();
                        city.stations = Math.max(0, result.stations());
                        stationCountingWarningLogged = false;
                        return new StationCountResult(city.stations, result.signs());
                    } else if (!stationCountingWarningLogged) {
                        plugin.getLogger().warning("Failed to refresh TrainCarts station count for city '" + city.name + "'; keeping the previous value.");
                        stationCountingWarningLogged = true;
                    }
                } catch (RuntimeException ex) {
                    if (!stationCountingWarningLogged) {
                        plugin.getLogger().log(Level.WARNING, "Unexpected error counting TrainCarts stations for city '" + city.name + "'", ex);
                        stationCountingWarningLogged = true;
                    }
                }
                return null;
            }
            case MANUAL -> {
                stationCountingWarningLogged = false;
                return null;
            }
        }
        return null;
    }

    private HappinessBreakdown calculateHappinessBreakdown(City city, City.BlockScanCache metrics) {
        return happinessCalculator.calculate(city, metrics);
    }

    private City.BlockScanCache ensureBlockScanCache(City city, boolean forceRefresh) {
        long now = System.currentTimeMillis();
        City.BlockScanCache cache = city.blockScanCache;
        boolean expired = cache == null || blockScanRefreshIntervalMillis <= 0
                || (now - cache.timestamp) >= blockScanRefreshIntervalMillis;
        if (forceRefresh || expired) {
            cache = recomputeBlockScanCache(city, now);
        }
        return cache;
    }

    private City.BlockScanCache recomputeBlockScanCache(City city, long now) {
        City.BlockScanCache cache = new City.BlockScanCache();
        cache.light = averageSurfaceLight(city);
        SampledRatio nature = natureRatio(city);
        cache.nature = nature.ratio();
        cache.natureSamples = nature.samples();
        PollutionStats pollutionStats = pollutionStats(city);
        cache.pollution = pollutionStats.ratio();
        cache.pollutingBlocks = pollutionStats.blockCount();
        cache.overcrowdingPenalty = happinessCalculator.computeOvercrowdingPenalty(city);
        cache.timestamp = now;
        city.blockScanCache = cache;
        return cache;
    }

    public void invalidateBlockScanCache(City city) {
        if (city != null) {
            city.invalidateBlockScanCache();
        }
    }

    public City.BlockScanCache refreshBlockScanCache(City city) {
        if (city == null) {
            return null;
        }
        return ensureBlockScanCache(city, true);
    }

    private double totalEffectiveArea(City city) {
        long sum = 0;
        for (Cuboid c : city.cuboids) {
            if (c == null || c.world == null) {
                continue;
            }
            long width = (long) (c.maxX - c.minX + 1);
            long length = (long) (c.maxZ - c.minZ + 1);
            long area = width * length;
            if (city.highrise) {
                long height = (long) (c.maxY - c.minY + 1);
                if (height < 1) height = 1;
                area *= height;
            }
            sum += area;
        }
        return (double) sum;
    }

    private double totalFootprintArea(City city) {
        long sum = 0;
        for (Cuboid c : city.cuboids) {
            if (c == null || c.world == null) {
                continue;
            }
            long width = (long) (c.maxX - c.minX + 1);
            long length = (long) (c.maxZ - c.minZ + 1);
            if (width < 0) width = 0;
            if (length < 0) length = 0;
            sum += width * length;
        }
        return (double) sum;
    }

    private double averageSurfaceLight(City city) {
        final int step = 8;
        if (!city.highrise) {
            Set<City.ChunkPosition> residentialChunks = city.getResidentialChunks();
            if (residentialChunks != null && !residentialChunks.isEmpty()) {
                int residentialSamples = 0;
                int residentialLightSum = 0;
                for (City.ChunkPosition chunkPos : residentialChunks) {
                    World world = Bukkit.getWorld(chunkPos.world());
                    if (world == null) {
                        continue;
                    }
                    Set<Long> sampledColumns = new HashSet<>();
                    int chunkMinX = chunkPos.x() << 4;
                    int chunkMaxX = chunkMinX + 15;
                    int chunkMinZ = chunkPos.z() << 4;
                    int chunkMaxZ = chunkMinZ + 15;
                    for (Cuboid cuboid : city.cuboids) {
                        if (cuboid == null || cuboid.world == null || !cuboid.world.equals(chunkPos.world())) {
                            continue;
                        }
                        int minX = Math.max(cuboid.minX, chunkMinX);
                        int maxX = Math.min(cuboid.maxX, chunkMaxX);
                        int minZ = Math.max(cuboid.minZ, chunkMinZ);
                        int maxZ = Math.min(cuboid.maxZ, chunkMaxZ);
                        if (minX > maxX || minZ > maxZ) {
                            continue;
                        }
                        for (int x = minX; x <= maxX; x += step) {
                            for (int z = minZ; z <= maxZ; z += step) {
                                long columnKey = (((long) x) << 32) | (z & 0xffffffffL);
                                if (!sampledColumns.add(columnKey)) {
                                    continue;
                                }
                                Integer blockLight = sampleSurfaceColumnBlockLight(world, x, z);
                                if (blockLight == null) {
                                    continue;
                                }
                                residentialLightSum += blockLight;
                                residentialSamples++;
                            }
                        }
                    }
                }
                if (residentialSamples > 0) {
                    return (double) residentialLightSum / residentialSamples;
                }
            }
        }

        int samples = 0;
        int lightSum = 0;
        for (Cuboid c : city.cuboids) {
            if (c == null || c.world == null) {
                continue;
            }
            World w = Bukkit.getWorld(c.world);
            if (w == null) continue;
            for (int x = c.minX; x <= c.maxX; x += step) {
                for (int z = c.minZ; z <= c.maxZ; z += step) {
                    if (city.highrise) {
                        for (int y = c.minY; y <= c.maxY; y += HIGHRISE_VERTICAL_STEP) {
                            lightSum += w.getBlockAt(x, y, z).getLightFromBlocks();
                            samples++;
                        }
                        if ((c.maxY - c.minY) % HIGHRISE_VERTICAL_STEP != 0) {
                            lightSum += w.getBlockAt(x, c.maxY, z).getLightFromBlocks();
                            samples++;
                        }
                    } else {
                        Integer blockLight = sampleSurfaceColumnBlockLight(w, x, z);
                        if (blockLight == null) {
                            continue;
                        }
                        lightSum += blockLight;
                        samples++;
                    }
                }
            }
        }
        return samples == 0 ? happinessCalculator.getLightNeutral() : (double) lightSum / samples;
    }

    private interface BlockTest { boolean test(org.bukkit.block.Block b); }

    private static class SurfaceSampleResult {
        final int found;
        final int probes;

        SurfaceSampleResult(int found, int probes) {
            this.found = found;
            this.probes = probes;
        }

        double ratio() {
            return probes == 0 ? 0.0 : (double) found / (double) probes;
        }
    }

    private static class ColumnSample {
        boolean sampled;
        boolean matched;
    }

    private record SampledRatio(double ratio, int samples) {}

    private SampledRatio ratioSurface(City city, int step, BlockTest test) {
        SurfaceSampleResult result = sampleSurface(city, step, test);
        return new SampledRatio(result.ratio(), result.probes);
    }

    private SampledRatio ratioHighriseColumns(City city, int step, BlockTest test) {
        Map<String, Map<Long, ColumnSample>> columns = new HashMap<>();
        for (Cuboid c : city.cuboids) {
            if (c == null || c.world == null) {
                continue;
            }
            World w = Bukkit.getWorld(c.world);
            if (w == null) continue;
            for (int x = c.minX; x <= c.maxX; x += step) {
                for (int z = c.minZ; z <= c.maxZ; z += step) {
                    Map<Long, ColumnSample> worldColumns = columns.computeIfAbsent(c.world, k -> new HashMap<>());
                    long columnKey = (((long) x) << 32) ^ (z & 0xffffffffL);
                    ColumnSample column = worldColumns.computeIfAbsent(columnKey, k -> new ColumnSample());
                    if (column.matched) {
                        column.sampled = true;
                        continue;
                    }
                    boolean sampled = false;
                    for (int y = c.minY; y <= c.maxY; y += HIGHRISE_VERTICAL_STEP) {
                        org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                        sampled = true;
                        if (test.test(b)) {
                            column.matched = true;
                            break;
                        }
                    }
                    if (!column.matched && (c.maxY - c.minY) % HIGHRISE_VERTICAL_STEP != 0) {
                        org.bukkit.block.Block b = w.getBlockAt(x, c.maxY, z);
                        sampled = true;
                        if (test.test(b)) {
                            column.matched = true;
                        }
                    }
                    if (sampled) {
                        column.sampled = true;
                    }
                }
            }
        }
        int totalColumns = 0;
        int columnsWithMatch = 0;
        for (Map<Long, ColumnSample> worldColumns : columns.values()) {
            for (ColumnSample column : worldColumns.values()) {
                if (!column.sampled) continue;
                totalColumns++;
                if (column.matched) {
                    columnsWithMatch++;
                }
            }
        }
        double ratio = totalColumns == 0 ? 0.0 : (double) columnsWithMatch / totalColumns;
        return new SampledRatio(ratio, totalColumns);
    }

    private SurfaceSampleResult sampleSurface(City city, int step, BlockTest test) {
        int found = 0, probes = 0;
        for (Cuboid c : city.cuboids) {
            if (c == null || c.world == null) {
                continue;
            }
            World w = Bukkit.getWorld(c.world);
            if (w == null) continue;
            for (int x = c.minX; x <= c.maxX; x += step) {
                for (int z = c.minZ; z <= c.maxZ; z += step) {
                    if (city.highrise) {
                        for (int y = c.minY; y <= c.maxY; y += HIGHRISE_VERTICAL_STEP) {
                            org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                            if (test.test(b)) found++;
                            probes++;
                        }
                        if ((c.maxY - c.minY) % HIGHRISE_VERTICAL_STEP != 0) {
                            org.bukkit.block.Block b = w.getBlockAt(x, c.maxY, z);
                            if (test.test(b)) found++;
                            probes++;
                        }
                    } else {
                        int y = w.getHighestBlockYAt(x, z);
                        org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                        if (test.test(b)) found++;
                        probes++;
                    }
                }
            }
        }
        return new SurfaceSampleResult(found, probes);
    }

    private SampledRatio natureRatio(City city) {
        BlockTest natureTest = b -> {
            org.bukkit.Material type = b.getType();
            if (org.bukkit.Tag.LOGS.isTagged(type) || org.bukkit.Tag.LEAVES.isTagged(type)) {
                return true;
            }
            return switch (type) {
                case GRASS_BLOCK, SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN,
                     VINE, LILY_PAD,
                     DANDELION, POPPY, BLUE_ORCHID, ALLIUM, AZURE_BLUET, RED_TULIP, ORANGE_TULIP, WHITE_TULIP, PINK_TULIP,
                     OXEYE_DAISY, CORNFLOWER, LILY_OF_THE_VALLEY, SUNFLOWER, PEONY, ROSE_BUSH -> true;
                default -> false;
            };
        };

        if (city.highrise) {
            return ratioHighriseColumns(city, 6, natureTest);
        }

        return ratioSurface(city, 6, natureTest);
    }

    private Integer sampleSurfaceColumnBlockLight(World world, int x, int z) {
        if (world == null) {
            return null;
        }

        int highestY = world.getHighestBlockYAt(x, z);
        if (highestY < world.getMinHeight()) {
            return null;
        }

        org.bukkit.block.Block surfaceBlock = world.getBlockAt(x, highestY, z);
        if (surfaceBlock.isLiquid()) {
            return null;
        }

        int maxHeight = world.getMaxHeight();
        int sampleStartY = highestY + 1;
        if (sampleStartY >= maxHeight) {
            return (int) surfaceBlock.getLightFromBlocks();
        }

        org.bukkit.block.Block sampleBlock = null;
        for (int y = sampleStartY; y < maxHeight; y++) {
            org.bukkit.block.Block candidate = world.getBlockAt(x, y, z);
            if (candidate.getType().isAir()) {
                sampleBlock = candidate;
                break;
            }
        }

        if (sampleBlock == null) {
            sampleBlock = surfaceBlock;
        }

        return (int) sampleBlock.getLightFromBlocks();
    }

    private record PollutionStats(double ratio, int blockCount) {}

    private PollutionStats pollutionStats(City city) {
        SurfaceSampleResult result = sampleSurface(city, 8, b -> switch (b.getType()) {
            case FURNACE, BLAST_FURNACE, SMOKER, CAMPFIRE, SOUL_CAMPFIRE, LAVA, LAVA_CAULDRON -> true;
            default -> false;
        });
        return new PollutionStats(result.ratio(), result.found);
    }

    private static boolean isBed(Material type) {
        return switch (type) {
            case WHITE_BED, ORANGE_BED, MAGENTA_BED, LIGHT_BLUE_BED, YELLOW_BED, LIME_BED, PINK_BED,
                 GRAY_BED, LIGHT_GRAY_BED, CYAN_BED, PURPLE_BED, BLUE_BED, BROWN_BED, GREEN_BED, RED_BED, BLACK_BED -> true;
            default -> false;
        };
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    public void updateConfig() {
        var c = plugin.getConfig();
        blockScanRefreshIntervalMillis = Math.max(0L, c.getLong("happiness.block_scan_refresh_interval_millis", 60000L));

        StationCountingMode configuredMode = StationCountingMode.fromConfig(c.getString("stations.counting_mode", "manual"));
        if (configuredMode == StationCountingMode.TRAIN_CARTS && stationCounter == null) {
            if (stationCountingMode != StationCountingMode.MANUAL) {
                plugin.getLogger().warning("TrainCarts station counting requested in configuration, but TrainCarts was not detected. Falling back to manual station counts.");
            }
            stationCountingMode = StationCountingMode.MANUAL;
        } else {
            stationCountingMode = configuredMode;
        }
        if (stationCountingMode != StationCountingMode.TRAIN_CARTS) {
            stationCountingWarningLogged = false;
        }

        long configuredInterval = c.getLong("updates.stats_interval_ticks", DEFAULT_STATS_INTERVAL_TICKS);
        if (configuredInterval < MIN_STATS_INTERVAL_TICKS || configuredInterval > MAX_STATS_INTERVAL_TICKS) {
            plugin.getLogger().warning("updates.stats_interval_ticks out of range; using default interval of " + DEFAULT_STATS_INTERVAL_TICKS + " ticks.");
            configuredInterval = DEFAULT_STATS_INTERVAL_TICKS;
        }
        statsIntervalTicks = configuredInterval;

        long configuredDelay = c.getLong("updates.stats_initial_delay_ticks", DEFAULT_STATS_INITIAL_DELAY_TICKS);
        if (configuredDelay < 0L || configuredDelay > MAX_STATS_INITIAL_DELAY_TICKS) {
            plugin.getLogger().warning("updates.stats_initial_delay_ticks out of range; using default delay of " + DEFAULT_STATS_INITIAL_DELAY_TICKS + " ticks.");
            configuredDelay = DEFAULT_STATS_INITIAL_DELAY_TICKS;
        }
        statsInitialDelayTicks = configuredDelay;

        int maxCitiesPerTick = Math.max(1, c.getInt("updates.max_cities_per_tick", 1));
        int maxEntityChunksPerTick = Math.max(1, c.getInt("updates.max_entity_chunks_per_tick", 2));
        int maxBedBlocksPerTick = Math.max(1, c.getInt("updates.max_bed_blocks_per_tick", 2048));
        scanScheduler.setLimits(maxCitiesPerTick, maxEntityChunksPerTick, maxBedBlocksPerTick);

        happinessCalculator.setLightNeutral(c.getDouble("happiness_weights.light_neutral_level", 2.0));
        happinessCalculator.setLightMaxPts(c.getDouble("happiness_weights.light_max_points", 10));
        happinessCalculator.setEmploymentMaxPts(c.getDouble("happiness_weights.employment_max_points", 15));
        happinessCalculator.setOvercrowdMaxPenalty(c.getDouble("happiness_weights.overcrowding_max_penalty", 10));
        happinessCalculator.setNatureMaxPts(c.getDouble("happiness_weights.nature_max_points", 10));
        happinessCalculator.setPollutionMaxPenalty(c.getDouble("happiness_weights.pollution_max_penalty", 15));
        happinessCalculator.setHousingMaxPts(c.getDouble("happiness_weights.housing_max_points", 10));
        happinessCalculator.setTransitMaxPts(c.getDouble("happiness_weights.transit_max_points", 5));
        happinessCalculator.setStationCountingMode(stationCountingMode);
    }

    private void scheduleTask() {
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, statsInitialDelayTicks, statsIntervalTicks);
    }

    private class StatsScanCallbacks implements CityScanCallbacks {
        @Override
        public StationCountResult refreshStationCount(City city) {
            return StatsService.this.refreshStationCount(city);
        }

        @Override
        public City.BlockScanCache ensureBlockScanCache(City city, boolean forceRefresh) {
            return StatsService.this.ensureBlockScanCache(city, forceRefresh);
        }

        @Override
        public HappinessBreakdown calculateHappinessBreakdown(City city, City.BlockScanCache cache) {
            return StatsService.this.calculateHappinessBreakdown(city, cache);
        }
    }
}
